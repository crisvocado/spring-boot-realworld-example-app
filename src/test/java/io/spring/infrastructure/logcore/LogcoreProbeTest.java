package io.spring.infrastructure.logcore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogcoreProbeTest {

  private static final Logger logger = LoggerFactory.getLogger(LogcoreProbeTest.class);

  @Test
  public void testEmitError() {
    LogcoreAppender.ensureAttached();
    logger.error(
        "Logcore probe test error emission",
        new RuntimeException("Logcore verification probe exception"));
    assertNotNull(logger);
  }

  @Test
  public void testProbeMain() {
    assertDoesNotThrow(() -> LogcoreProbe.main(new String[0]));
  }
}
