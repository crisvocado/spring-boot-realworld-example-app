package io.spring.logcore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LogcoreHttpTransport {

  private static final int BUFFER_CAP = 1000;
  private static final int BATCH_SIZE = 20;
  private static final int MAX_ENTRIES_PER_REQUEST = 200;
  private static final int FLUSH_INTERVAL_SECONDS = 5;
  private static final int MAX_RETRIES = 3;
  private static final int CIRCUIT_BREAKER_THRESHOLD = 10;

  private final Queue<Map<String, Object>> buffer = new ArrayDeque<>();
  private final ScheduledExecutorService scheduler;
  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private volatile Consumer<String> customSender = null;
  private volatile boolean closed = false;

  private static volatile LogcoreHttpTransport instance;

  public static LogcoreHttpTransport getInstance() {
    if (instance == null) {
      synchronized (LogcoreHttpTransport.class) {
        if (instance == null) {
          instance = new LogcoreHttpTransport();
        }
      }
    }
    return instance;
  }

  public LogcoreHttpTransport() {
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "logcore-http-transport");
              t.setDaemon(true);
              return t;
            });

    this.scheduler.scheduleWithFixedDelay(
        this::flushAsync,
        FLUSH_INTERVAL_SECONDS,
        FLUSH_INTERVAL_SECONDS,
        TimeUnit.SECONDS);

    Runtime.getRuntime().addShutdownHook(new Thread(this::flushAndClose, "logcore-shutdown"));
  }

  public void setCustomSender(Consumer<String> sender) {
    this.customSender = sender;
  }

  public void enqueue(Map<String, Object> entry) {
    if (entry == null || closed) {
      return;
    }

    boolean shouldFlush = false;
    synchronized (buffer) {
      if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
        buffer.clear();
        return;
      }
      while (buffer.size() >= BUFFER_CAP) {
        buffer.poll(); // drop oldest
      }
      buffer.offer(entry);
      if (buffer.size() >= BATCH_SIZE) {
        shouldFlush = true;
      }
    }

    if (shouldFlush && !scheduler.isShutdown()) {
      scheduler.execute(this::flushAsync);
    }
  }

  private void flushAsync() {
    try {
      flushInternal();
    } catch (Throwable t) {
      // Never throw out of async flush
    }
  }

  public void flushSync() {
    flushInternal();
  }

  private void flushInternal() {
    List<Map<String, Object>> batch = new ArrayList<>();
    synchronized (buffer) {
      if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
        buffer.clear();
        return;
      }
      while (!buffer.isEmpty() && batch.size() < MAX_ENTRIES_PER_REQUEST) {
        Map<String, Object> entry = buffer.poll();
        if (entry != null) {
          batch.add(entry);
        }
      }
    }

    if (batch.isEmpty()) {
      return;
    }

    String jsonPayload;
    try {
      jsonPayload = LogcorePayloadSerializer.serializeEnvelope(batch);
    } catch (Exception e) {
      System.err.println("Logcore serialization error: " + e.getMessage());
      return;
    }

    Consumer<String> sender = this.customSender;
    if (sender != null) {
      try {
        sender.accept(jsonPayload);
        consecutiveFailures.set(0);
      } catch (Exception e) {
        System.err.println("Logcore custom sender error: " + e.getMessage());
      }
      return;
    }

    LogcoreConfig config = LogcoreConfig.getInstance();
    if (!config.isEnabled() || config.getApiKey().isEmpty()) {
      return;
    }

    sendWithRetry(jsonPayload, config);
  }

  private void sendWithRetry(String jsonPayload, LogcoreConfig config) {
    String endpoint = config.getEndpoint();
    String apiKey = config.getApiKey();

    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      HttpURLConnection conn = null;
      try {
        URL url = new URL(endpoint);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);

        byte[] body = jsonPayload.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = conn.getOutputStream()) {
          os.write(body);
          os.flush();
        }

        int statusCode = conn.getResponseCode();

        if (statusCode >= 200 && statusCode < 300) {
          consecutiveFailures.set(0);
          return;
        }

        if (statusCode == 429 || (statusCode >= 500 && statusCode < 600)) {
          if (attempt < MAX_RETRIES) {
            long backoff = (1L << attempt) * 250L;
            try {
              Thread.sleep(backoff);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              break;
            }
            continue;
          } else {
            recordFailure();
            return;
          }
        }

        // Permanent rejection for any other 4xx status code
        String responseBody = readStream(conn.getErrorStream());
        System.err.println(
            "Logcore delivery rejected (HTTP " + statusCode + "): " + responseBody);
        return;

      } catch (Exception e) {
        if (attempt < MAX_RETRIES) {
          long backoff = (1L << attempt) * 250L;
          try {
            Thread.sleep(backoff);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        } else {
          recordFailure();
        }
      } finally {
        if (conn != null) {
          conn.disconnect();
        }
      }
    }
  }

  private void recordFailure() {
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
      synchronized (buffer) {
        buffer.clear();
      }
    }
  }

  private String readStream(InputStream is) {
    if (is == null) {
      return "";
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append('\n');
      }
      return sb.toString().trim();
    } catch (Exception e) {
      return "";
    }
  }

  public void flushAndClose() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      flushInternal();
    } catch (Exception ignored) {
    }
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException ie) {
      scheduler.shutdownNow();
    }
  }
}
