package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogcoreProbe {

  private static final Logger logger = LoggerFactory.getLogger(LogcoreProbe.class);

  public static void main(String[] args) {
    ObjectMapper objectMapper = new ObjectMapper();
    LogcoreTransport stdoutTransport =
        (envelope, ingestUrl, apiKey) -> {
          if (envelope != null && envelope.getEntries() != null) {
            for (LogcoreEntry entry : envelope.getEntries()) {
              System.out.println(objectMapper.writeValueAsString(entry));
            }
          }
        };

    LogcoreConfig config = new LogcoreConfig();
    config.setEnabled(true);
    LogcoreClient probeClient = new LogcoreClient(config, stdoutTransport);
    LogcoreClient.setInstance(probeClient);

    ch.qos.logback.classic.LoggerContext context =
        (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
    for (ch.qos.logback.classic.Logger l : context.getLoggerList()) {
      l.detachAndStopAllAppenders();
    }
    ch.qos.logback.classic.Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    LogcoreAppender appender = new LogcoreAppender(probeClient);
    appender.setContext(context);
    appender.start();
    rootLogger.addAppender(appender);

    TraceContextHolder.set(TraceContext.generate());

    try {
      throw new RuntimeException("Logcore verification probe error");
    } catch (Exception e) {
      logger.error("Logcore probe error emission", e);
    } finally {
      TraceContextHolder.clear();
    }

    probeClient.flush();
    probeClient.close();
  }
}
