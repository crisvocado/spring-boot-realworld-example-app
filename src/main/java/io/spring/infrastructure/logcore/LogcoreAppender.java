package io.spring.infrastructure.logcore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class LogcoreAppender extends AppenderBase<ILoggingEvent> {

  public static final String APPENDER_NAME = "LOGCORE";
  private static final int MAX_MESSAGE_LENGTH = 65536;
  private static final int MAX_LABELS_COUNT = 32;
  private static final int MAX_LABEL_VALUE_LENGTH = 1024;
  private static final int MAX_STACK_FRAMES = 50;

  private final LogcoreClient client;
  private final ObjectMapper objectMapper;
  private Level minLevel;

  public LogcoreAppender() {
    this(LogcoreClient.getInstance());
  }

  public LogcoreAppender(LogcoreClient client) {
    this.client = client != null ? client : LogcoreClient.getInstance();
    this.objectMapper =
        new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    String severity = this.client.getConfig().getMinSeverity();
    this.minLevel = Level.toLevel(severity, Level.ERROR);
    setName(APPENDER_NAME);
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null) {
      return;
    }

    if (!event.getLevel().isGreaterOrEqual(minLevel)) {
      return;
    }

    try {
      LogcoreEntry entry = buildEntry(event);
      client.enqueue(entry);
    } catch (Throwable ignored) {
      // Cardinal rule: logging must never break or block the app
    }
  }

  public LogcoreEntry buildEntry(ILoggingEvent event) {
    LogcoreConfig config = client.getConfig();

    String timestamp =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.getTimeStamp()));
    String severity = event.getLevel() != null ? event.getLevel().toString() : "ERROR";

    String rawMessage = event.getFormattedMessage();
    String message = rawMessage != null ? rawMessage : "";
    if (message.length() > MAX_MESSAGE_LENGTH) {
      message = message.substring(0, MAX_MESSAGE_LENGTH);
    }

    String service = config.getService();
    String env = config.getEnv();

    LogcoreError error = extractError(event);
    LogcoreTrace trace = extractTrace();
    Map<String, String> labels = extractLabels(event);
    Map<String, Object> context = null;

    String insertId = computeInsertId(timestamp, service, severity, message, context);

    LogcoreEntry entry = new LogcoreEntry();
    entry.setTimestamp(timestamp);
    entry.setSeverity(severity);
    entry.setMessage(message);
    entry.setService(service);
    entry.setEnv(env);
    entry.setInsertId(insertId);
    entry.setTrace(trace);
    entry.setError(error);
    entry.setLabels(labels);
    entry.setContext(context);

    return entry;
  }

  private LogcoreError extractError(ILoggingEvent event) {
    IThrowableProxy tp = event.getThrowableProxy();
    if (tp == null) {
      return null;
    }

    // Follow the exception chain to its root cause
    IThrowableProxy rootCause = tp;
    while (rootCause.getCause() != null) {
      rootCause = rootCause.getCause();
    }

    String fullClassName = tp.getClassName();
    String type = fullClassName;
    if (fullClassName != null) {
      int lastDot = fullClassName.lastIndexOf('.');
      if (lastDot >= 0 && lastDot < fullClassName.length() - 1) {
        type = fullClassName.substring(lastDot + 1);
      }
    }
    if (type == null || type.isEmpty()) {
      type = "Error";
    }

    String message = tp.getMessage();
    if (message == null) {
      message = rootCause.getMessage();
    }
    if (message == null) {
      message = "";
    }

    List<LogcoreStackFrame> stack = new ArrayList<>();
    StackTraceElementProxy[] stepArray = rootCause.getStackTraceElementProxyArray();
    if (stepArray != null) {
      for (int i = 0; i < stepArray.length && i < MAX_STACK_FRAMES; i++) {
        StackTraceElement ste = stepArray[i].getStackTraceElement();
        if (ste != null) {
          String className = ste.getClassName() != null ? ste.getClassName() : "";
          String methodName = ste.getMethodName() != null ? ste.getMethodName() : "";
          String function = className.isEmpty() ? methodName : className + "." + methodName;
          String file = ste.getFileName() != null ? ste.getFileName() : "unknown";
          int line = ste.getLineNumber() > 0 ? ste.getLineNumber() : 0;
          int column = 0;
          boolean inApp = className.startsWith("io.spring.");
          stack.add(new LogcoreStackFrame(function, file, line, column, inApp));
        }
      }
    }

    return new LogcoreError(type, message, stack);
  }

  private LogcoreTrace extractTrace() {
    TraceContext current = TraceContextHolder.get();
    if (current != null) {
      return new LogcoreTrace(current.getTraceId(), current.getSpanId(), current.isSampled());
    }

    String traceId = MDC.get(TraceContextFilter.MDC_TRACE_ID);
    String spanId = MDC.get(TraceContextFilter.MDC_SPAN_ID);
    String sampledStr = MDC.get(TraceContextFilter.MDC_SAMPLED);
    if (traceId != null && !traceId.isEmpty()) {
      boolean sampled = "true".equalsIgnoreCase(sampledStr);
      return new LogcoreTrace(traceId, spanId != null ? spanId : "", sampled);
    }

    return null;
  }

  private Map<String, String> extractLabels(ILoggingEvent event) {
    Map<String, String> labels = new HashMap<>();

    Map<String, String> mdc = event.getMDCPropertyMap();
    if (mdc != null && !mdc.isEmpty()) {
      for (Map.Entry<String, String> e : mdc.entrySet()) {
        if (labels.size() >= MAX_LABELS_COUNT) {
          break;
        }
        String k = e.getKey();
        String v = e.getValue();
        if (k != null && v != null) {
          if (v.length() > MAX_LABEL_VALUE_LENGTH) {
            v = v.substring(0, MAX_LABEL_VALUE_LENGTH);
          }
          labels.put(k, v);
        }
      }
    }

    if (labels.size() < MAX_LABELS_COUNT && event.getLoggerName() != null) {
      labels.put("logger", event.getLoggerName());
    }
    if (labels.size() < MAX_LABELS_COUNT && event.getThreadName() != null) {
      labels.put("thread", event.getThreadName());
    }

    return labels.isEmpty() ? null : labels;
  }

  public String computeInsertId(
      String timestamp,
      String service,
      String severity,
      String message,
      Map<String, Object> context) {
    String contextJson = "{}";
    if (context != null && !context.isEmpty()) {
      try {
        contextJson = objectMapper.writeValueAsString(new TreeMap<>(context));
      } catch (Exception e) {
        contextJson = "{}";
      }
    }
    String raw =
        (timestamp != null ? timestamp : "")
            + "|"
            + (service != null ? service : "")
            + "|"
            + (severity != null ? severity : "")
            + "|"
            + (message != null ? message : "")
            + "|"
            + contextJson;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      return UUID.randomUUID().toString().replace("-", "");
    }
  }

  public static synchronized void ensureAttached() {
    try {
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      Iterator<Appender<ILoggingEvent>> appenderIterator = rootLogger.iteratorForAppenders();
      while (appenderIterator.hasNext()) {
        Appender<ILoggingEvent> appender = appenderIterator.next();
        if (APPENDER_NAME.equals(appender.getName()) || appender instanceof LogcoreAppender) {
          return;
        }
      }
      LogcoreAppender appender = new LogcoreAppender();
      appender.setContext(context);
      appender.start();
      rootLogger.addAppender(appender);
    } catch (Throwable ignored) {
    }
  }
}
