package io.spring.infrastructure.logging;

import ch.qos.logback.classic.LoggerContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogcoreProbe {

  public static void main(String[] args) {
    try {
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      ch.qos.logback.classic.Logger rootLogger =
          context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

      // Ensure LogcoreAppender is attached
      LogcoreAppender appender = (LogcoreAppender) rootLogger.getAppender("LOGCORE");
      if (appender == null) {
        appender = new LogcoreAppender(LogcoreClient.getInstance());
        appender.setName("LOGCORE");
        appender.setContext(context);
        appender.start();
        rootLogger.addAppender(appender);
      }

      // Detach console appender during probe so only the JSON entry is printed to stdout
      rootLogger.detachAppender("CONSOLE");
      rootLogger.detachAppender("console");

      LogcoreClient client = LogcoreClient.getInstance();
      ObjectMapper mapper = new ObjectMapper();
      AtomicReference<String> entryJsonRef = new AtomicReference<>();

      client.setCustomSender(
          payload -> {
            try {
              JsonNode root = mapper.readTree(payload);
              JsonNode entries = root.get("entries");
              if (entries != null && entries.isArray() && entries.size() > 0) {
                entryJsonRef.set(mapper.writeValueAsString(entries.get(0)));
              } else {
                entryJsonRef.set(payload);
              }
            } catch (Exception e) {
              entryJsonRef.set(payload);
            }
          });

      Logger logger = LoggerFactory.getLogger(LogcoreProbe.class);
      logger.error(
          "Logcore probe test error", new RuntimeException("Logcore probe test exception"));

      client.flushSync(2000);

      String entryJson = entryJsonRef.get();
      if (entryJson != null && !entryJson.isEmpty()) {
        System.out.println(entryJson);
      }
    } catch (Throwable t) {
      t.printStackTrace(System.err);
      System.exit(1);
    }
  }
}
