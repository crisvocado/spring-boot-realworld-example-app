package io.spring.logcore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogcoreProbe {
  private static final Logger logger = LoggerFactory.getLogger(LogcoreProbe.class);

  public static void emitTestError() {
    logger.error(
        "Logcore probe verification error",
        new RuntimeException("Logcore verification probe exception"));
    LogcoreAppender.flushAll();
  }

  public static void main(String[] args) {
    emitTestError();
  }
}
