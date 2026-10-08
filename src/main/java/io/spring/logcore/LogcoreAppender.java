package io.spring.logcore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.Map;
import java.util.function.Consumer;

public class LogcoreAppender extends AppenderBase<ILoggingEvent> {

  private static volatile LogcoreAppender activeInstance;

  private final LogcoreConfig config;
  private final LogcorePayloadBuilder payloadBuilder;
  private final LogcoreHttpTransport transport;

  public LogcoreAppender() {
    this.config = new LogcoreConfig();
    this.payloadBuilder = new LogcorePayloadBuilder(this.config);
    this.transport = new LogcoreHttpTransport(this.config, this.payloadBuilder);
    activeInstance = this;
  }

  public static void setCaptureHook(Consumer<String> hook) {
    if (activeInstance != null) {
      activeInstance.transport.setCaptureHook(hook);
    }
  }

  public static void flushSync() {
    if (activeInstance != null) {
      activeInstance.transport.flushSync();
    }
  }

  public static LogcoreAppender getActiveInstance() {
    return activeInstance;
  }

  public LogcoreHttpTransport getTransport() {
    return transport;
  }

  @Override
  public void start() {
    activeInstance = this;
    super.start();
  }

  @Override
  public void stop() {
    transport.stop();
    super.stop();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null || event.getLevel() == null) {
      return;
    }

    if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) {
      return;
    }

    try {
      Map<String, Object> entry = payloadBuilder.buildEntry(event);
      transport.enqueue(entry);
    } catch (Throwable t) {
      System.err.println("Logcore appender error: " + t.getMessage());
    }
  }
}
