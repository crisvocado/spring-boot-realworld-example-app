package io.spring.logcore;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogcoreProbeTest {

  private static final Logger log = LoggerFactory.getLogger(LogcoreProbeTest.class);

  @Test
  public void testEmitLogcore() {
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger rootLogger = lc.getLogger(Logger.ROOT_LOGGER_NAME);
    Appender<ILoggingEvent> appender = rootLogger.getAppender("LOGCORE");
    if (appender == null && LogcoreAppender.getActiveInstance() == null) {
      LogcoreAppender la = new LogcoreAppender();
      la.setContext(lc);
      la.setName("LOGCORE");
      la.start();
      rootLogger.addAppender(la);
    }

    AtomicReference<String> captured = new AtomicReference<>();
    LogcoreAppender.setCaptureHook(captured::set);

    try {
      log.error("Logcore probe test error", new RuntimeException("Probe exception"));
      LogcoreAppender.flushSync();

      String payload = captured.get();
      if (payload == null || payload.isBlank()) {
        throw new IllegalStateException("Logcore probe failed: no payload captured");
      }

      System.out.println(payload);
    } finally {
      LogcoreAppender.setCaptureHook(null);
    }
  }
}
