package io.spring.infrastructure.logcore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.LoggerFactory;

public class LogcoreAppender extends AppenderBase<ILoggingEvent> {

  private static final int MAX_MESSAGE_LENGTH = 65536;
  private static final int MAX_LABELS = 32;
  private static final int MAX_LABEL_LENGTH = 1024;
  private static final int MAX_STACK_FRAMES = 50;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private LogcoreConfig config;
  private LogcoreClient client;
  private Level minLevel = Level.ERROR;

  public LogcoreAppender() {}

  public LogcoreAppender(LogcoreConfig config, LogcoreClient client) {
    this.config = config;
    this.client = client;
    this.minLevel = parseMinLevel(config.getMinSeverity());
  }

  @Override
  public void start() {
    if (config == null) {
      config = new LogcoreConfig();
      minLevel = parseMinLevel(config.getMinSeverity());
    }
    if (client == null) {
      client = new LogcoreClient(config);
    }
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    if (client != null) {
      client.close();
    }
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null || !isStarted()) {
      return;
    }

    if (!event.getLevel().isGreaterOrEqual(minLevel)) {
      return;
    }

    try {
      LogcoreEntry entry = formatEntry(event);
      if (entry != null && client != null) {
        client.enqueue(entry);
      }
    } catch (Exception e) {
      // Logging must never crash the application
      System.err.println("[logcore] Failed to process log event: " + e.getMessage());
    }
  }

  public LogcoreEntry formatEntry(ILoggingEvent event) {
    LogcoreEntry entry = new LogcoreEntry();

    String timestamp = Instant.ofEpochMilli(event.getTimeStamp()).toString();
    entry.setTimestamp(timestamp);

    String severity = event.getLevel().toString();
    entry.setSeverity(severity);

    String message = event.getFormattedMessage();
    if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
      message = message.substring(0, MAX_MESSAGE_LENGTH);
    }
    entry.setMessage(message);

    entry.setService(config != null ? config.getService() : "spring-boot-realworld-example-app");
    entry.setEnv(config != null ? config.getEnv() : "dev");

    // Trace extraction from MDC
    Map<String, String> mdc = event.getMDCPropertyMap();
    TraceContext trace = extractTraceContext(mdc);
    if (trace != null) {
      entry.setTrace(trace);
    }

    // Error extraction
    ErrorDetail error = extractErrorDetail(event);
    if (error != null) {
      entry.setError(error);
    }

    // Labels & Context
    Map<String, String> labels = extractLabels(event, mdc);
    if (!labels.isEmpty()) {
      entry.setLabels(labels);
    }

    Map<String, Object> context = extractContext(mdc);
    if (!context.isEmpty()) {
      entry.setContext(context);
    }

    // Insert ID
    String insertId = computeInsertId(timestamp, entry.getService(), severity, message, context);
    entry.setInsertId(insertId);

    return entry;
  }

  private TraceContext extractTraceContext(Map<String, String> mdc) {
    if (mdc == null || mdc.isEmpty()) {
      return null;
    }

    String traceparent = mdc.get("traceparent");
    if (traceparent != null && !traceparent.isEmpty()) {
      // Format: 00-<32 hex traceId>-<16 hex spanId>-<2 hex flags>
      String[] parts = traceparent.split("-");
      if (parts.length >= 4) {
        String traceId = parts[1];
        String spanId = parts[2];
        boolean sampled = "01".equals(parts[3]) || (parts[3].endsWith("1"));
        return new TraceContext(traceId, spanId, sampled);
      }
    }

    String traceId = mdc.get("traceId");
    if (traceId == null) traceId = mdc.get("trace_id");
    if (traceId == null) traceId = mdc.get("X-B3-TraceId");

    String spanId = mdc.get("spanId");
    if (spanId == null) spanId = mdc.get("span_id");
    if (spanId == null) spanId = mdc.get("X-B3-SpanId");

    if (traceId != null && spanId != null) {
      String sampledStr = mdc.get("traceSampled");
      if (sampledStr == null) sampledStr = mdc.get("sampled");
      boolean sampled = (sampledStr == null || Boolean.parseBoolean(sampledStr));
      return new TraceContext(traceId, spanId, sampled);
    }

    return null;
  }

  private ErrorDetail extractErrorDetail(ILoggingEvent event) {
    IThrowableProxy proxy = event.getThrowableProxy();
    if (proxy != null) {
      String type = proxy.getClassName();
      String message = proxy.getMessage();

      List<StackFrame> frames = new ArrayList<>();
      // Unroll cause chain to root and place root cause frames first
      List<IThrowableProxy> chain = new ArrayList<>();
      IThrowableProxy cur = proxy;
      Set<IThrowableProxy> visited = Collections.newSetFromMap(new IdentityHashMap<>());
      while (cur != null && visited.add(cur)) {
        chain.add(cur);
        cur = cur.getCause();
      }

      // Reverse chain so root cause is index 0
      Collections.reverse(chain);

      for (IThrowableProxy throwableProxy : chain) {
        StackTraceElementProxy[] stepArray = throwableProxy.getStackTraceElementProxyArray();
        if (stepArray != null) {
          for (StackTraceElementProxy step : stepArray) {
            if (frames.size() >= MAX_STACK_FRAMES) {
              break;
            }
            StackTraceElement ste = step.getStackTraceElement();
            frames.add(toStackFrame(ste));
          }
        }
        if (frames.size() >= MAX_STACK_FRAMES) {
          break;
        }
      }

      return new ErrorDetail(type, message, frames);
    }

    // Fallback to caller data if available
    StackTraceElement[] callerData = event.getCallerData();
    if (callerData != null && callerData.length > 0) {
      List<StackFrame> frames = new ArrayList<>();
      for (StackTraceElement ste : callerData) {
        if (frames.size() >= MAX_STACK_FRAMES) {
          break;
        }
        if (ste.getClassName().contains("io.spring.infrastructure.logcore")) {
          continue;
        }
        frames.add(toStackFrame(ste));
      }
      if (!frames.isEmpty()) {
        return new ErrorDetail("Error", event.getFormattedMessage(), frames);
      }
    }

    return null;
  }

  private StackFrame toStackFrame(StackTraceElement ste) {
    String function = ste.getMethodName();
    String file = ste.getFileName() != null ? ste.getFileName() : ste.getClassName();
    int line = Math.max(0, ste.getLineNumber());
    int column = 0;
    boolean inApp = isInApp(ste.getClassName());
    return new StackFrame(function, file, line, column, inApp);
  }

  private boolean isInApp(String className) {
    if (className == null) return false;
    if (className.startsWith("io.spring.infrastructure.logcore.LogcoreAppender")
        || className.startsWith("io.spring.infrastructure.logcore.LogcoreClient")
        || className.startsWith("io.spring.infrastructure.logcore.LogcoreTraceFilter")
        || className.startsWith("io.spring.infrastructure.logcore.LogcoreConfig")) {
      return false;
    }
    return className.startsWith("io.spring.");
  }

  private Map<String, String> extractLabels(ILoggingEvent event, Map<String, String> mdc) {
    Map<String, String> labels = new HashMap<>();

    if (event.getLoggerName() != null) {
      labels.put("logger", clamp(event.getLoggerName(), MAX_LABEL_LENGTH));
    }
    if (event.getThreadName() != null) {
      labels.put("thread", clamp(event.getThreadName(), MAX_LABEL_LENGTH));
    }

    if (mdc != null) {
      for (Map.Entry<String, String> e : mdc.entrySet()) {
        if (labels.size() >= MAX_LABELS) {
          break;
        }
        String k = e.getKey();
        if (isReservedMdcKey(k)) {
          continue;
        }
        if (k != null && e.getValue() != null) {
          labels.put(clamp(k, MAX_LABEL_LENGTH), clamp(e.getValue(), MAX_LABEL_LENGTH));
        }
      }
    }

    return labels;
  }

  private Map<String, Object> extractContext(Map<String, String> mdc) {
    Map<String, Object> context = new HashMap<>();
    if (mdc != null) {
      for (Map.Entry<String, String> e : mdc.entrySet()) {
        String k = e.getKey();
        if (k != null
            && (k.startsWith("context.")
                || k.equals("userId")
                || k.equals("username")
                || k.equals("requestId"))) {
          String key = k.startsWith("context.") ? k.substring("context.".length()) : k;
          context.put(key, e.getValue());
        }
      }
    }
    return context;
  }

  private boolean isReservedMdcKey(String key) {
    return "traceparent".equals(key)
        || "traceId".equals(key)
        || "trace_id".equals(key)
        || "spanId".equals(key)
        || "span_id".equals(key)
        || "traceSampled".equals(key)
        || "sampled".equals(key)
        || "X-B3-TraceId".equals(key)
        || "X-B3-SpanId".equals(key)
        || key.startsWith("context.")
        || "userId".equals(key)
        || "username".equals(key)
        || "requestId".equals(key);
  }

  private String clamp(String val, int maxLen) {
    if (val == null) return null;
    return val.length() <= maxLen ? val : val.substring(0, maxLen);
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
        TreeMap<String, Object> sorted = new TreeMap<>(context);
        contextJson = objectMapper.writeValueAsString(sorted);
      } catch (JsonProcessingException ignored) {
        contextJson = "{}";
      }
    }

    String raw =
        timestamp
            + "|"
            + service
            + "|"
            + severity
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
      return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }
  }

  private Level parseMinLevel(String minSeverity) {
    if (minSeverity == null) return Level.ERROR;
    switch (minSeverity.toUpperCase()) {
      case "TRACE":
        return Level.TRACE;
      case "DEBUG":
        return Level.DEBUG;
      case "INFO":
        return Level.INFO;
      case "WARN":
      case "WARNING":
        return Level.WARN;
      case "ERROR":
      case "FATAL":
      case "CRITICAL":
      default:
        return Level.ERROR;
    }
  }

  public static void ensureAttached() {
    try {
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      Logger rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME);
      if (rootLogger.getAppender("LOGCORE") == null) {
        LogcoreAppender appender = new LogcoreAppender();
        appender.setName("LOGCORE");
        appender.setContext(context);
        appender.start();
        rootLogger.addAppender(appender);
      }
    } catch (Exception e) {
      System.err.println("[logcore] Could not attach appender to root logger: " + e.getMessage());
    }
  }

  public LogcoreClient getClient() {
    return client;
  }
}
