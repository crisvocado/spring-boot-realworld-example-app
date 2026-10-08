package io.spring.logcore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LogcoreHttpTransport {

  private static final int BUFFER_CAPACITY = 1000;
  private static final int BATCH_SIZE = 20;
  private static final int MAX_ENTRIES_PER_REQUEST = 200;
  private static final int CIRCUIT_BREAKER_THRESHOLD = 10;
  private static final int MAX_RETRIES = 3;

  private final LogcoreConfig config;
  private final LogcorePayloadBuilder payloadBuilder;
  private final BlockingQueue<Map<String, Object>> buffer;
  private final HttpClient httpClient;
  private final ScheduledExecutorService scheduler;
  private final AtomicInteger consecutiveFailures;
  private final AtomicBoolean isCircuitOpen;
  private final AtomicBoolean running;
  private final Thread shutdownHook;

  private volatile Consumer<String> captureHook;

  public LogcoreHttpTransport(LogcoreConfig config, LogcorePayloadBuilder payloadBuilder) {
    this.config = config;
    this.payloadBuilder = payloadBuilder;
    this.buffer = new LinkedBlockingQueue<>(BUFFER_CAPACITY);
    this.consecutiveFailures = new AtomicInteger(0);
    this.isCircuitOpen = new AtomicBoolean(false);
    this.running = new AtomicBoolean(true);

    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "logcore-http-transport");
              t.setDaemon(true);
              return t;
            });

    this.scheduler.scheduleWithFixedDelay(this::flushQuietly, 5, 5, TimeUnit.SECONDS);

    this.shutdownHook = new Thread(this::stop, "logcore-shutdown-hook");
    try {
      Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    } catch (IllegalStateException ignored) {
      // JVM is already shutting down
    }
  }

  public void enqueue(Map<String, Object> entry) {
    if (!running.get()) {
      return;
    }

    if (isCircuitOpen.get()) {
      return;
    }

    while (!buffer.offer(entry)) {
      buffer.poll(); // drop oldest
    }

    if (buffer.size() >= BATCH_SIZE || captureHook != null) {
      scheduler.execute(this::flushQuietly);
    }
  }

  public void setCaptureHook(Consumer<String> hook) {
    this.captureHook = hook;
  }

  public void flushSync() {
    flushBuffer();
  }

  public void stop() {
    if (running.compareAndSet(true, false)) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (Exception ignored) {
      }
      flushSync();
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  private void flushQuietly() {
    try {
      flushBuffer();
    } catch (Throwable t) {
      System.err.println("Logcore: unexpected error during flush: " + t.getMessage());
    }
  }

  private void flushBuffer() {
    if (isCircuitOpen.get() && captureHook == null) {
      buffer.clear();
      return;
    }

    List<Map<String, Object>> batch = new ArrayList<>(MAX_ENTRIES_PER_REQUEST);
    while (batch.size() < MAX_ENTRIES_PER_REQUEST) {
      Map<String, Object> entry = buffer.poll();
      if (entry == null) {
        break;
      }
      batch.add(entry);
    }

    if (batch.isEmpty()) {
      return;
    }

    String jsonEnvelope = payloadBuilder.buildEnvelopeJson(batch);

    Consumer<String> hook = this.captureHook;
    if (hook != null) {
      try {
        hook.accept(jsonEnvelope);
      } catch (Throwable t) {
        System.err.println("Logcore capture hook error: " + t.getMessage());
      }
      return;
    }

    if (!config.isEnabled() || config.getApiKey().isEmpty()) {
      return;
    }

    sendWithRetry(jsonEnvelope);
  }

  private void sendWithRetry(String jsonBody) {
    int attempt = 0;
    while (attempt <= MAX_RETRIES) {
      try {
        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          consecutiveFailures.set(0);
          isCircuitOpen.set(false);
          return;
        } else if (status == 429 || status >= 500) {
          attempt++;
          if (attempt <= MAX_RETRIES) {
            long backoffMs = (long) (Math.pow(2, attempt) * 250);
            Thread.sleep(backoffMs);
          }
        } else {
          // Permanent 4xx rejection (400, 401, 403, 422, etc.)
          System.err.println(
              "Logcore permanent rejection (" + status + "): " + response.body());
          recordFailure();
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        recordFailure();
        return;
      } catch (Exception e) {
        attempt++;
        if (attempt <= MAX_RETRIES) {
          long backoffMs = (long) (Math.pow(2, attempt) * 250);
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            recordFailure();
            return;
          }
        }
      }
    }

    recordFailure();
  }

  private void recordFailure() {
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
      isCircuitOpen.set(true);
      buffer.clear();
      System.err.println(
          "Logcore circuit breaker tripped after " + failures + " consecutive failed flushes.");
    }
  }
}
