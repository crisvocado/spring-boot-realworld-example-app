package io.spring.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LogcoreAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private final LogcoreClient client;

  public LogcoreAppender() {
    this(LogcoreClient.getInstance());
  }

  public LogcoreAppender(LogcoreClient client) {
    this.client = client;
  }

  @Override
  public void start() {
    super.start();
  }

  @Override
  public void stop() {
    if (client != null) {
      client.flushSync(2000);
    }
    super.stop();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null || client == null) {
      return;
    }
    LogcoreConfig config = client.getConfig();
    if (!config.isEnabled()) {
      return;
    }
    if (event.getLevel() == null || !event.getLevel().isGreaterOrEqual(config.getMinLevel())) {
      return;
    }

    Map<String, Object> entry = buildEntry(event, config);
    client.enqueue(entry);
  }

  public static Map<String, Object> buildEntry(ILoggingEvent event, LogcoreConfig config) {
    Map<String, Object> entry = new LinkedHashMap<>();

    // 1. timestamp
    String timestamp = Instant.ofEpochMilli(event.getTimeStamp()).toString();
    entry.put("timestamp", timestamp);

    // 2. severity
    String severity = formatSeverity(event.getLevel());
    entry.put("severity", severity);

    // 3. message
    String message = event.getFormattedMessage();
    if (message == null) {
      message = "";
    }
    if (message.length() > config.getMaxMessageLength()) {
      message = message.substring(0, config.getMaxMessageLength());
    }
    entry.put("message", message);

    // 4. service
    entry.put("service", config.getService());

    // 5. env
    entry.put("env", config.getEnv());

    // 6. context & trace
    Map<String, String> mdc = event.getMDCPropertyMap();
    Map<String, Object> context = new LinkedHashMap<>();
    String traceId = null;
    String spanId = null;
    Boolean sampled = null;

    if (mdc != null && !mdc.isEmpty()) {
      for (Map.Entry<String, String> e : mdc.entrySet()) {
        String k = e.getKey();
        String v = e.getValue();
        if (v == null) continue;
        if ("traceparent".equalsIgnoreCase(k)) {
          String[] parts = v.split("-");
          if (parts.length >= 4) {
            traceId = parts[1];
            spanId = parts[2];
            sampled = "01".equals(parts[3]) || (parts[3].endsWith("1"));
          }
        } else if ("traceId".equalsIgnoreCase(k)
            || "trace_id".equalsIgnoreCase(k)
            || "x-trace-id".equalsIgnoreCase(k)
            || "x-b3-traceid".equalsIgnoreCase(k)) {
          traceId = v;
        } else if ("spanId".equalsIgnoreCase(k)
            || "span_id".equalsIgnoreCase(k)
            || "x-span-id".equalsIgnoreCase(k)
            || "x-b3-spanid".equalsIgnoreCase(k)) {
          spanId = v;
        } else if ("sampled".equalsIgnoreCase(k)) {
          sampled = Boolean.parseBoolean(v);
        } else {
          context.put(k, v);
        }
      }
    }

    // 7. insert_id
    String insertId =
        LogcoreClient.computeInsertId(timestamp, config.getService(), severity, message, context);
    entry.put("insert_id", insertId);

    // 8. trace (optional)
    if (traceId != null && !traceId.isEmpty()) {
      Map<String, Object> trace = new LinkedHashMap<>();
      trace.put("traceId", traceId);
      if (spanId != null && !spanId.isEmpty()) {
        trace.put("spanId", spanId);
      }
      trace.put("sampled", sampled != null ? sampled : true);
      entry.put("trace", trace);
    }

    // 9. error (optional)
    IThrowableProxy tp = event.getThrowableProxy();
    if (tp != null) {
      Map<String, Object> error = buildErrorFromThrowableProxy(tp, config);
      entry.put("error", error);
    } else if (event.getCallerData() != null && event.getCallerData().length > 0) {
      Map<String, Object> error = buildErrorFromCallerData(event.getCallerData(), message, config);
      if (error != null) {
        entry.put("error", error);
      }
    }

    // 10. labels (optional)
    Map<String, String> labels = new LinkedHashMap<>();
    if (event.getThreadName() != null && !event.getThreadName().isEmpty()) {
      labels.put("thread", clamp(event.getThreadName(), config.getMaxLabelValueLength()));
    }
    if (event.getLoggerName() != null && !event.getLoggerName().isEmpty()) {
      labels.put("logger", clamp(event.getLoggerName(), config.getMaxLabelValueLength()));
    }
    if (!labels.isEmpty()) {
      entry.put("labels", labels);
    }

    // 11. context (optional)
    if (!context.isEmpty()) {
      entry.put("context", context);
    }

    return entry;
  }

  private static Map<String, Object> buildErrorFromThrowableProxy(
      IThrowableProxy tp, LogcoreConfig config) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("type", tp.getClassName());
    error.put("message", tp.getMessage() != null ? tp.getMessage() : "");

    // Follow cause chain to root cause, root at index 0
    List<IThrowableProxy> chain = new ArrayList<>();
    IThrowableProxy curr = tp;
    while (curr != null) {
      chain.add(0, curr);
      curr = curr.getCause();
    }

    List<Map<String, Object>> frames = new ArrayList<>();
    for (IThrowableProxy t : chain) {
      StackTraceElementProxy[] steps = t.getStackTraceElementProxyArray();
      if (steps != null) {
        for (StackTraceElementProxy step : steps) {
          if (frames.size() >= config.getMaxStackFrames()) {
            break;
          }
          StackTraceElement ste = step.getStackTraceElement();
          if (ste != null) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("function", ste.getClassName() + "." + ste.getMethodName());
            frame.put(
                "file",
                ste.getFileName() != null
                    ? ste.getFileName()
                    : ste.getClassName().replace('.', '/') + ".java");
            frame.put("line", ste.getLineNumber() > 0 ? ste.getLineNumber() : 1);
            frame.put("column", 0);
            frame.put("inApp", isInApp(ste.getClassName()));
            frames.add(frame);
          }
        }
      }
      if (frames.size() >= config.getMaxStackFrames()) {
        break;
      }
    }

    error.put("stack", frames);
    return error;
  }

  private static Map<String, Object> buildErrorFromCallerData(
      StackTraceElement[] callerData, String message, LogcoreConfig config) {
    List<Map<String, Object>> frames = new ArrayList<>();
    for (StackTraceElement ste : callerData) {
      if (frames.size() >= config.getMaxStackFrames()) {
        break;
      }
      if (ste.getClassName().equals("io.spring.infrastructure.logging.LogcoreAppender")
          || ste.getClassName().equals("io.spring.infrastructure.logging.LogcoreClient")
          || ste.getClassName().startsWith("io.spring.infrastructure.logging.LogcoreAppender$")
          || ste.getClassName().startsWith("io.spring.infrastructure.logging.LogcoreClient$")) {
        continue;
      }
      Map<String, Object> frame = new LinkedHashMap<>();
      frame.put("function", ste.getClassName() + "." + ste.getMethodName());
      frame.put(
          "file",
          ste.getFileName() != null
              ? ste.getFileName()
              : ste.getClassName().replace('.', '/') + ".java");
      frame.put("line", ste.getLineNumber() > 0 ? ste.getLineNumber() : 1);
      frame.put("column", 0);
      frame.put("inApp", isInApp(ste.getClassName()));
      frames.add(frame);
    }
    if (frames.isEmpty()) {
      return null;
    }
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("type", "Error");
    error.put("message", message);
    error.put("stack", frames);
    return error;
  }

  private static String formatSeverity(Level level) {
    if (level == null) return "ERROR";
    if (level.isGreaterOrEqual(Level.ERROR)) return "ERROR";
    if (level.isGreaterOrEqual(Level.WARN)) return "WARN";
    if (level.isGreaterOrEqual(Level.INFO)) return "INFO";
    return "DEBUG";
  }

  public static boolean isInApp(String className) {
    if (className == null) return false;
    if (className.equals("io.spring.infrastructure.logging.LogcoreAppender")
        || className.equals("io.spring.infrastructure.logging.LogcoreClient")
        || className.equals("io.spring.infrastructure.logging.LogcoreConfig")
        || className.startsWith("io.spring.infrastructure.logging.LogcoreAppender$")
        || className.startsWith("io.spring.infrastructure.logging.LogcoreClient$")) {
      return false;
    }
    return className.startsWith("io.spring.") || className.startsWith("io.spring");
  }

  private static String clamp(String str, int maxLen) {
    if (str == null) return "";
    return str.length() <= maxLen ? str : str.substring(0, maxLen);
  }
}
