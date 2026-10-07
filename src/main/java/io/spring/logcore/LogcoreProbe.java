package io.spring.logcore;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogcoreProbe {

  public static void main(String[] args) {
    AtomicReference<String> captured = new AtomicReference<>();
    LogcoreHttpTransport transport = LogcoreHttpTransport.getInstance();
    transport.setCustomSender(captured::set);

    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext) {
      LoggerContext context = (LoggerContext) factory;
      ch.qos.logback.classic.Logger rootLogger =
          context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      if (rootLogger != null) {
        List<Appender<ILoggingEvent>> toDetach = new ArrayList<>();
        Iterator<Appender<ILoggingEvent>> it = rootLogger.iteratorForAppenders();
        while (it.hasNext()) {
          Appender<ILoggingEvent> appender = it.next();
          if (!LogcoreAppender.APPENDER_NAME.equals(appender.getName())) {
            toDetach.add(appender);
          }
        }
        for (Appender<ILoggingEvent> appender : toDetach) {
          rootLogger.detachAppender(appender);
          appender.stop();
        }
      }
    }

    LogcoreAppender.install();

    Logger logger = LoggerFactory.getLogger(LogcoreProbe.class);
    logger.error(
        "Logcore integration probe execution",
        new RuntimeException("Logcore synthetic probe error"));

    transport.flushSync();

    String result = captured.get();
    if (result == null || result.trim().isEmpty()) {
      System.err.println("Logcore probe failed: no output captured");
      System.exit(1);
    }

    System.out.println(result);
  }
}
