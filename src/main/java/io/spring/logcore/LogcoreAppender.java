package io.spring.logcore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.util.Map;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

public class LogcoreAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  public static final String APPENDER_NAME = "LOGCORE";

  private final LogcoreConfig config;
  private final LogcoreHttpTransport transport;

  public LogcoreAppender() {
    this.config = LogcoreConfig.getInstance();
    this.transport = LogcoreHttpTransport.getInstance();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null || !isStarted()) {
      return;
    }

    String loggerName = event.getLoggerName();
    if (loggerName != null && loggerName.equals(LogcoreHttpTransport.class.getName())) {
      return;
    }

    Level level = event.getLevel();
    if (level == null || level.toInt() < Level.ERROR_INT) {
      return;
    }

    try {
      Map<String, Object> entry = LogcorePayloadSerializer.serializeEvent(event, config);
      transport.enqueue(entry);
    } catch (Throwable t) {
      // Never propagate logging exceptions
    }
  }

  public static synchronized void install() {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext) {
      LoggerContext context = (LoggerContext) factory;
      Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      if (rootLogger != null && rootLogger.getAppender(APPENDER_NAME) == null) {
        LogcoreAppender appender = new LogcoreAppender();
        appender.setName(APPENDER_NAME);
        appender.setContext(context);
        appender.start();
        rootLogger.addAppender(appender);
      }
    }
  }

  public static synchronized void uninstall() {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext) {
      LoggerContext context = (LoggerContext) factory;
      Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      if (rootLogger != null) {
        ch.qos.logback.core.Appender<ILoggingEvent> appender =
            rootLogger.getAppender(APPENDER_NAME);
        if (appender != null) {
          rootLogger.detachAppender(appender);
          appender.stop();
        }
      }
    }
  }
}
