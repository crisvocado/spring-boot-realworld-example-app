package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LogcoreClient implements AutoCloseable {

  private static final int MAX_BUFFER_CAPACITY = 1000;
  private static final int BATCH_SIZE_TRIGGER = 20;
  private static final int MAX_BATCH_SIZE = 200;
  private static final int MAX_RETRIES = 3;
  private static final int CIRCUIT_BREAKER_THRESHOLD = 10;
  private static final long FLUSH_INTERVAL_SECONDS = 5;

  private final LogcoreConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ConcurrentLinkedDeque<LogcoreEntry> buffer;
  private final ScheduledExecutorService scheduler;
  private final AtomicInteger consecutiveFailures;
  private final AtomicBoolean circuitBreakerOpen;
  private final AtomicBoolean isFlushing;
  private final AtomicBoolean isClosed;
  private final URI ingestUri;
  private final Thread shutdownHook;

  public LogcoreClient(LogcoreConfig config) {
    this(config, createDefaultHttpClient(), new ObjectMapper());
  }

  public LogcoreClient(LogcoreConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
    this.config = config;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.buffer = new ConcurrentLinkedDeque<>();
    this.consecutiveFailures = new AtomicInteger(0);
    this.circuitBreakerOpen = new AtomicBoolean(false);
    this.isFlushing = new AtomicBoolean(false);
    this.isClosed = new AtomicBoolean(false);

    String rawUrl = config.getUrl().replaceAll("/+$", "");
    if (!rawUrl.endsWith("/v1/logs")) {
      rawUrl = rawUrl + "/v1/logs";
    }
    this.ingestUri = URI.create(rawUrl);

    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "logcore-flusher");
                t.setDaemon(true);
                return t;
              }
            });

    this.scheduler.scheduleWithFixedDelay(
        this::flush, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

    this.shutdownHook = new Thread(this::close, "logcore-shutdown");
    try {
      Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    } catch (IllegalStateException ignored) {
      // JVM is already shutting down
    }
  }

  private static HttpClient createDefaultHttpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public void enqueue(LogcoreEntry entry) {
    if (!config.isEnabled() || isClosed.get()) {
      return;
    }
    if (circuitBreakerOpen.get()) {
      return;
    }

    while (buffer.size() >= MAX_BUFFER_CAPACITY) {
      buffer.pollFirst(); // drop oldest
    }
    buffer.addLast(entry);

    if (buffer.size() >= BATCH_SIZE_TRIGGER) {
      triggerAsyncFlush();
    }
  }

  public void triggerAsyncFlush() {
    if (!isClosed.get() && !scheduler.isShutdown()) {
      scheduler.execute(this::flush);
    }
  }

  public synchronized void flush() {
    if (isFlushing.getAndSet(true)) {
      return;
    }
    try {
      while (!buffer.isEmpty()) {
        List<LogcoreEntry> batch = new ArrayList<>(MAX_BATCH_SIZE);
        for (int i = 0; i < MAX_BATCH_SIZE; i++) {
          LogcoreEntry entry = buffer.pollFirst();
          if (entry == null) {
            break;
          }
          batch.add(entry);
        }

        if (batch.isEmpty()) {
          break;
        }

        boolean success = sendBatchWithRetries(batch);
        if (!success) {
          // If sending failed and circuit breaker tripped or batch dropped, break out of loop
          break;
        }
      }
    } finally {
      isFlushing.set(false);
    }
  }

  private boolean sendBatchWithRetries(List<LogcoreEntry> batch) {
    if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
      // Without API key, drop batch in dev/offline
      return true;
    }

    String jsonBody;
    try {
      LogcoreEnvelope envelope = new LogcoreEnvelope(batch);
      jsonBody = objectMapper.writeValueAsString(envelope);
    } catch (Exception e) {
      System.err.println("[logcore] Failed to serialize batch to JSON: " + e.getMessage());
      return false;
    }

    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      if (attempt > 0) {
        long backoffMs = (long) (Math.pow(2, attempt - 1) * 250);
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }

      try {
        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(ingestUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
          consecutiveFailures.set(0);
          circuitBreakerOpen.set(false);
          return true;
        }

        if (statusCode == 429 || statusCode >= 500) {
          // Transient error: retry
          continue;
        }

        // Permanent rejection (4xx other than 429)
        System.err.println(
            "[logcore] Permanent rejection (HTTP " + statusCode + "): " + response.body());
        // Do not retry, drop batch
        return false;
      } catch (IOException e) {
        // Network error: retry
        if (attempt == MAX_RETRIES) {
          System.err.println("[logcore] Network error sending log batch: " + e.getMessage());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception e) {
        System.err.println("[logcore] Unexpected error sending log batch: " + e.getMessage());
        return false;
      }
    }

    // All retries failed
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
      circuitBreakerOpen.set(true);
      buffer.clear();
      System.err.println(
          "[logcore] Circuit breaker tripped after "
              + failures
              + " consecutive failed flushes. Buffer cleared.");
    }
    return false;
  }

  public URI getIngestUri() {
    return ingestUri;
  }

  public int getBufferSize() {
    return buffer.size();
  }

  public boolean isCircuitBreakerOpen() {
    return circuitBreakerOpen.get();
  }

  @Override
  public void close() {
    if (isClosed.getAndSet(true)) {
      return;
    }
    try {
      flush();
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
