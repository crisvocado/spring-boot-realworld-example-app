package io.spring.infrastructure.logcore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class LogcoreProbe {

  public static void main(String[] args) {
    try {
      LogcoreConfig config = new LogcoreConfig();
      LogcoreAppender appender = new LogcoreAppender(config, null);
      appender.start();

      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      Logger logger = context.getLogger("io.spring.infrastructure.logcore.LogcoreProbe");

      MDC.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
      MDC.put("userId", "probe-user");

      Exception cause = new IllegalArgumentException("Root cause error");
      Exception top = new RuntimeException("checkout failed", cause);

      LoggingEvent event =
          new LoggingEvent(
              "io.spring.infrastructure.logcore.LogcoreProbe",
              logger,
              Level.ERROR,
              "checkout failed",
              top,
              null);
      event.setTimeStamp(System.currentTimeMillis());
      event.setMDCPropertyMap(MDC.getCopyOfContextMap());

      LogcoreEntry entry = appender.formatEntry(event);

      ObjectMapper objectMapper = new ObjectMapper();
      String json = objectMapper.writeValueAsString(entry);
      System.out.println(json);
    } catch (Exception e) {
      System.err.println("[logcore-probe] Failed: " + e.getMessage());
      System.exit(1);
    } finally {
      MDC.clear();
    }
  }
}
