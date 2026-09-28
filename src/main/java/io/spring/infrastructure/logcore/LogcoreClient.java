package io.spring.infrastructure.logcore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LogcoreClient implements AutoCloseable {

  private static final int BUFFER_CAPACITY = 1000;
  private static final int BATCH_SIZE_TRIGGER = 20;
  private static final int MAX_BATCH_SIZE = 200;
  private static final int CIRCUIT_BREAKER_THRESHOLD = 10;
  private static final long FLUSH_INTERVAL_SECONDS = 5;

  private static volatile LogcoreClient instance;

  private final LogcoreConfig config;
  private final LogcoreTransport transport;
  private final ConcurrentLinkedDeque<LogcoreEntry> buffer;
  private final ScheduledExecutorService scheduler;
  private final AtomicInteger consecutiveFailures;
  private final AtomicBoolean isFlushing;
  private final AtomicBoolean closed;
  private final Thread shutdownHook;

  public static LogcoreClient getInstance() {
    if (instance == null) {
      synchronized (LogcoreClient.class) {
        if (instance == null) {
          instance = new LogcoreClient(new LogcoreConfig(), new HttpLogcoreTransport());
        }
      }
    }
    return instance;
  }

  public static void setInstance(LogcoreClient customInstance) {
    synchronized (LogcoreClient.class) {
      if (instance != null && instance != customInstance) {
        instance.close();
      }
      instance = customInstance;
    }
  }

  public LogcoreClient(LogcoreConfig config, LogcoreTransport transport) {
    this.config = config != null ? config : new LogcoreConfig();
    this.transport = transport != null ? transport : new HttpLogcoreTransport();
    this.buffer = new ConcurrentLinkedDeque<>();
    this.consecutiveFailures = new AtomicInteger(0);
    this.isFlushing = new AtomicBoolean(false);
    this.closed = new AtomicBoolean(false);

    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "logcore-sender");
              t.setDaemon(true);
              return t;
            });

    this.scheduler.scheduleWithFixedDelay(
        this::flushSafely, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

    this.shutdownHook = new Thread(this::close, "logcore-shutdown");
    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } catch (IllegalStateException ignored) {
      // JVM is already shutting down
    }
  }

  public void enqueue(LogcoreEntry entry) {
    if (entry == null || closed.get()) {
      return;
    }

    // Buffer capacity check: drop oldest if buffer is full
    while (buffer.size() >= BUFFER_CAPACITY) {
      buffer.pollFirst();
    }

    buffer.offerLast(entry);

    // If batch trigger reached, request async flush
    if (buffer.size() >= BATCH_SIZE_TRIGGER && !isFlushing.get()) {
      scheduler.execute(this::flushSafely);
    }
  }

  public void flush() {
    if (!config.isEnabled() || buffer.isEmpty()) {
      return;
    }

    if (consecutiveFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
      // Circuit breaker is open - drop buffer and warn
      buffer.clear();
      System.err.println(
          "Logcore circuit breaker active: dropping buffer after "
              + CIRCUIT_BREAKER_THRESHOLD
              + " consecutive failures.");
      return;
    }

    List<LogcoreEntry> batch = new ArrayList<>(MAX_BATCH_SIZE);
    for (int i = 0; i < MAX_BATCH_SIZE; i++) {
      LogcoreEntry entry = buffer.pollFirst();
      if (entry == null) {
        break;
      }
      batch.add(entry);
    }

    if (batch.isEmpty()) {
      return;
    }

    try {
      LogcoreEnvelope envelope = new LogcoreEnvelope(batch);
      transport.send(envelope, config.getIngestUrl(), config.getKey());
      // Successful flush resets circuit breaker streak
      consecutiveFailures.set(0);
    } catch (Exception e) {
      int failures = consecutiveFailures.incrementAndGet();
      System.err.println(
          "Logcore flush failed ("
              + failures
              + "/"
              + CIRCUIT_BREAKER_THRESHOLD
              + "): "
              + e.getMessage());
      if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
        buffer.clear();
        System.err.println(
            "Logcore circuit breaker tripped: emptied buffer after "
                + failures
                + " consecutive failures.");
      }
    }
  }

  private void flushSafely() {
    if (isFlushing.compareAndSet(false, true)) {
      try {
        flush();
      } catch (Throwable t) {
        System.err.println("Logcore unexpected flush error: " + t.getMessage());
      } finally {
        isFlushing.set(false);
      }
    }
  }

  public LogcoreConfig getConfig() {
    return config;
  }

  public int getBufferSize() {
    return buffer.size();
  }

  public int getConsecutiveFailures() {
    return consecutiveFailures.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        flushSafely();
      } catch (Throwable ignored) {
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
    }
  }
}
