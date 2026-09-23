package io.spring.logcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LogcoreClient {
  private static final int MAX_BUFFER_CAPACITY = 1000;
  private static final int BATCH_SIZE_TRIGGER = 20;
  private static final int MAX_BATCH_SIZE = 200;
  private static final int MAX_RETRIES = 3;
  private static final int CIRCUIT_BREAKER_THRESHOLD = 10;
  private static final long FLUSH_INTERVAL_SECONDS = 5;

  private final LogcoreConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ConcurrentLinkedQueue<LogcoreEntry> buffer;
  private final AtomicInteger bufferSize;
  private final AtomicInteger consecutiveFailures;
  private final AtomicBoolean circuitBreakerOpen;
  private final ScheduledExecutorService scheduler;
  private final Thread shutdownHook;
  private final AtomicBoolean isClosed;

  public LogcoreClient(LogcoreConfig config) {
    this(
        config,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        new ObjectMapper());
  }

  public LogcoreClient(LogcoreConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
    this.config = config;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.buffer = new ConcurrentLinkedQueue<>();
    this.bufferSize = new AtomicInteger(0);
    this.consecutiveFailures = new AtomicInteger(0);
    this.circuitBreakerOpen = new AtomicBoolean(false);
    this.isClosed = new AtomicBoolean(false);

    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "logcore-flusher");
              t.setDaemon(true);
              return t;
            });

    this.scheduler.scheduleWithFixedDelay(
        this::flushBuffer,
        FLUSH_INTERVAL_SECONDS,
        FLUSH_INTERVAL_SECONDS,
        TimeUnit.SECONDS);

    this.shutdownHook = new Thread(this::close, "logcore-shutdown");
    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } catch (IllegalStateException ignored) {
      // JVM is already shutting down
    }
  }

  public void enqueue(LogcoreEntry entry) {
    if (entry == null || isClosed.get()) {
      return;
    }

    // Cap buffer at 1000 entries - drop oldest when full
    while (bufferSize.get() >= MAX_BUFFER_CAPACITY) {
      if (buffer.poll() != null) {
        bufferSize.decrementAndGet();
      } else {
        break;
      }
    }

    buffer.offer(entry);
    int currentSize = bufferSize.incrementAndGet();

    // Trigger flush if batch size threshold reached
    if (currentSize >= BATCH_SIZE_TRIGGER) {
      scheduler.execute(this::flushBuffer);
    }
  }

  public void flushBuffer() {
    if (isClosed.get() && buffer.isEmpty()) {
      return;
    }

    if (circuitBreakerOpen.get()) {
      if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
        // Buffer is emptied when circuit breaker trips
        buffer.clear();
        bufferSize.set(0);
        return;
      } else {
        circuitBreakerOpen.set(false);
      }
    }

    List<LogcoreEntry> batch = new ArrayList<>(MAX_BATCH_SIZE);
    while (batch.size() < MAX_BATCH_SIZE) {
      LogcoreEntry entry = buffer.poll();
      if (entry == null) {
        break;
      }
      bufferSize.decrementAndGet();
      batch.add(entry);
    }

    if (batch.isEmpty()) {
      return;
    }

    sendBatch(batch);
  }

  private void sendBatch(List<LogcoreEntry> batch) {
    if (!config.isEnabled() || config.getApiKey().isEmpty()) {
      // If transport is not configured or disabled, drop silently
      return;
    }

    String endpoint = config.getUrl() + "/v1/logs";
    String payload;
    try {
      LogcoreEntry.Envelope envelope = new LogcoreEntry.Envelope(batch);
      payload = objectMapper.writeValueAsString(envelope);
    } catch (Exception e) {
      System.err.println("[logcore] Failed to serialize log envelope: " + e.getMessage());
      return;
    }

    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder()
              .uri(URI.create(endpoint))
              .header("Content-Type", "application/json")
              .header("x-api-key", config.getApiKey())
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
    } catch (Exception e) {
      System.err.println("[logcore] Invalid request URI or headers: " + e.getMessage());
      return;
    }

    int totalAttempts = MAX_RETRIES + 1;
    for (int attempt = 0; attempt < totalAttempts; attempt++) {
      try {
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
          consecutiveFailures.set(0);
          circuitBreakerOpen.set(false);
          return;
        }

        if (status == 429 || status >= 500) {
          // Retryable error
          if (attempt < MAX_RETRIES) {
            long backoffMillis = (long) Math.pow(2, attempt) * 250L;
            try {
              Thread.sleep(backoffMillis);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return;
            }
            continue;
          } else {
            handleFailure("[logcore] Exhausted retries for log delivery (HTTP " + status + "): " + response.body());
            return;
          }
        } else {
          // Permanent 4xx rejection (e.g. 400, 401, 403, 422) - do NOT retry
          handleFailure("[logcore] Permanent rejection (HTTP " + status + "): " + response.body());
          return;
        }
      } catch (IOException e) {
        if (attempt < MAX_RETRIES) {
          long backoffMillis = (long) Math.pow(2, attempt) * 250L;
          try {
            Thread.sleep(backoffMillis);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
          }
        } else {
          handleFailure("[logcore] Network error delivering log batch: " + e.getMessage());
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void handleFailure(String message) {
    System.err.println(message);
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
      if (circuitBreakerOpen.compareAndSet(false, true)) {
        System.err.println(
            "[logcore] Circuit breaker tripped after "
                + failures
                + " consecutive failures. Emptying buffer and suspending delivery.");
        buffer.clear();
        bufferSize.set(0);
      }
    }
  }

  public int getBufferSize() {
    return bufferSize.get();
  }

  public void close() {
    if (isClosed.compareAndSet(false, true)) {
      try {
        flushBuffer();
      } catch (Exception ignored) {
      }
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (Exception ignored) {
      }
    }
  }
}
