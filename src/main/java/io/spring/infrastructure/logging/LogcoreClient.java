package io.spring.infrastructure.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

public class LogcoreClient implements AutoCloseable {

  private static final LogcoreClient INSTANCE = new LogcoreClient();

  private final LogcoreConfig config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final ArrayDeque<Map<String, Object>> queue;
  private final ScheduledExecutorService scheduler;
  private final AtomicInteger consecutiveFailures;
  private final AtomicBoolean closed;
  private volatile long lastFlushTime;
  private volatile Consumer<String> customSender;

  public static LogcoreClient getInstance() {
    return INSTANCE;
  }

  public LogcoreClient() {
    this(new LogcoreConfig());
  }

  public LogcoreClient(LogcoreConfig config) {
    this.config = config;
    this.objectMapper = new ObjectMapper();
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    this.queue = new ArrayDeque<>();
    this.consecutiveFailures = new AtomicInteger(0);
    this.closed = new AtomicBoolean(false);
    this.lastFlushTime = System.currentTimeMillis();

    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "logcore-sender");
                t.setDaemon(true);
                return t;
              }
            });

    this.scheduler.scheduleWithFixedDelay(
        this::checkFlushInterval, 1000, 1000, TimeUnit.MILLISECONDS);

    installCrashAndShutdownHooks();
  }

  private void installCrashAndShutdownHooks() {
    try {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    flushSync(3000);
                    close();
                  },
                  "logcore-shutdown-hook"));
    } catch (Throwable ignored) {
    }

    try {
      Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
      Thread.setDefaultUncaughtExceptionHandler(
          (t, e) -> {
            try {
              LoggerFactory.getLogger("io.spring.UncaughtException")
                  .error("Uncaught exception in thread " + t.getName(), e);
              flushSync(2000);
            } catch (Throwable ignored) {
            } finally {
              if (prev != null) {
                prev.uncaughtException(t, e);
              }
            }
          });
    } catch (Throwable ignored) {
    }
  }

  public LogcoreConfig getConfig() {
    return config;
  }

  public void setCustomSender(Consumer<String> customSender) {
    this.customSender = customSender;
  }

  public void enqueue(Map<String, Object> entry) {
    if (closed.get() || entry == null) {
      return;
    }
    boolean triggerFlush = false;
    synchronized (queue) {
      while (queue.size() >= config.getQueueCapacity()) {
        queue.pollFirst(); // drop oldest
      }
      queue.offerLast(entry);
      if (queue.size() >= config.getBatchSize()) {
        triggerFlush = true;
      }
    }
    if (triggerFlush) {
      scheduler.execute(this::flushInternal);
    }
  }

  private void checkFlushInterval() {
    if (closed.get()) {
      return;
    }
    long now = System.currentTimeMillis();
    boolean shouldFlush = false;
    synchronized (queue) {
      if (!queue.isEmpty() && (now - lastFlushTime >= config.getFlushIntervalMs())) {
        shouldFlush = true;
      }
    }
    if (shouldFlush) {
      flushInternal();
    }
  }

  public void flushSync(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      boolean empty;
      synchronized (queue) {
        empty = queue.isEmpty();
      }
      if (empty) {
        break;
      }
      flushInternal();
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void flushInternal() {
    List<Map<String, Object>> batch = new ArrayList<>();
    synchronized (queue) {
      if (consecutiveFailures.get() >= config.getCircuitBreakerThreshold()) {
        queue.clear();
        return;
      }
      int count = 0;
      while (!queue.isEmpty() && count < config.getMaxBatchSize()) {
        batch.add(queue.pollFirst());
        count++;
      }
      lastFlushTime = System.currentTimeMillis();
    }
    if (batch.isEmpty()) {
      return;
    }
    sendBatch(batch);
  }

  private void sendBatch(List<Map<String, Object>> batch) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schema_version", 1);
    envelope.put("entries", batch);

    String jsonPayload;
    try {
      jsonPayload = objectMapper.writeValueAsString(envelope);
    } catch (Exception e) {
      System.err.println("Logcore failed to serialize batch: " + e.getMessage());
      return;
    }

    if (customSender != null) {
      try {
        customSender.accept(jsonPayload);
        consecutiveFailures.set(0);
      } catch (Exception e) {
        handleFailure(e.getMessage());
      }
      return;
    }

    if (!config.isEnabled()) {
      return;
    }

    for (int attempt = 0; attempt <= config.getMaxRetries(); attempt++) {
      try {
        HttpRequest.Builder reqBuilder =
            HttpRequest.newBuilder()
                .uri(URI.create(config.getIngestUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

        if (config.getKey() != null && !config.getKey().isEmpty()) {
          reqBuilder.header("x-api-key", config.getKey());
        }

        HttpResponse<String> response =
            httpClient.send(
                reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int statusCode = response.statusCode();

        if (statusCode >= 200 && statusCode < 300) {
          consecutiveFailures.set(0);
          return;
        } else if (statusCode == 429 || statusCode >= 500) {
          if (attempt < config.getMaxRetries()) {
            long backoff = (1L << attempt) * 250L;
            try {
              Thread.sleep(backoff);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return;
            }
            continue;
          } else {
            handleFailure("HTTP " + statusCode + " after " + (attempt + 1) + " attempts");
            return;
          }
        } else {
          // Permanent 4xx error (e.g. 400, 401, 403, 404, 422)
          System.err.println("Logcore rejected batch (" + statusCode + "): " + response.body());
          return;
        }
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        if (attempt < config.getMaxRetries()) {
          long backoff = (1L << attempt) * 250L;
          try {
            Thread.sleep(backoff);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
          }
        } else {
          handleFailure("Network error: " + e.getMessage());
          return;
        }
      }
    }
  }

  private void handleFailure(String reason) {
    int fails = consecutiveFailures.incrementAndGet();
    if (fails >= config.getCircuitBreakerThreshold()) {
      synchronized (queue) {
        queue.clear();
      }
      System.err.println(
          "Logcore circuit breaker tripped after "
              + fails
              + " consecutive flush failures. Emptied buffer.");
    } else {
      System.err.println("Logcore flush failed: " + reason);
    }
  }

  public static String canonicalJson(Map<String, ?> map) {
    if (map == null || map.isEmpty()) {
      return "{}";
    }
    List<String> keys = new ArrayList<>(map.keySet());
    Collections.sort(keys);
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (String key : keys) {
      Object val = map.get(key);
      if (val == null) continue;
      if (!first) sb.append(",");
      sb.append("\"").append(escapeJson(key)).append("\":");
      if (val instanceof Number || val instanceof Boolean) {
        sb.append(val);
      } else if (val instanceof Map) {
        sb.append(canonicalJson((Map<String, ?>) val));
      } else {
        sb.append("\"").append(escapeJson(val.toString())).append("\"");
      }
      first = false;
    }
    sb.append("}");
    return sb.toString();
  }

  public static String escapeJson(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 32) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  public static String computeInsertId(
      String timestamp, String service, String severity, String message, Map<String, ?> context) {
    String payload =
        timestamp + "|" + service + "|" + severity + "|" + message + "|" + canonicalJson(context);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not available", e);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException ie) {
        scheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
}
