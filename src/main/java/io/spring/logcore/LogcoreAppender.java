package io.spring.logcore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogcoreAppender extends AppenderBase<ILoggingEvent> {
  private static final Pattern TRACEPARENT_PATTERN =
      Pattern.compile("^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");
  private static final int MAX_MESSAGE_LENGTH = 65536;
  private static final int MAX_LABELS = 32;
  private static final int MAX_LABEL_LENGTH = 1024;
  private static final int MAX_STACK_FRAMES = 50;

  private static final List<LogcoreAppender> ACTIVE_APPENDERS = new CopyOnWriteArrayList<>();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private LogcoreConfig config;
  private LogcoreClient client;

  @Override
  public void start() {
    if (config == null) {
      config = new LogcoreConfig();
    }
    if (client == null) {
      client = new LogcoreClient(config);
    }
    ACTIVE_APPENDERS.add(this);
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    ACTIVE_APPENDERS.remove(this);
    if (client != null) {
      client.close();
    }
  }

  public void setConfig(LogcoreConfig config) {
    this.config = config;
  }

  public void setClient(LogcoreClient client) {
    this.client = client;
  }

  public LogcoreClient getClient() {
    return client;
  }

  public LogcoreConfig getConfig() {
    return config;
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event == null) {
      return;
    }

    if (config == null) {
      config = new LogcoreConfig();
    }
    if (client == null) {
      client = new LogcoreClient(config);
    }

    Level minLevel = Level.toLevel(config.getMinSeverity(), Level.ERROR);
    if (event.getLevel().toInt() < minLevel.toInt()) {
      return;
    }

    try {
      LogcoreEntry entry = buildEntry(event);
      client.enqueue(entry);
    } catch (Exception e) {
      System.err.println("[logcore] Failed to process log event: " + e.getMessage());
    }
  }

  public LogcoreEntry buildEntry(ILoggingEvent event) {
    String timestamp = Instant.ofEpochMilli(event.getTimeStamp()).toString();
    String severity = event.getLevel().toString();

    String rawMessage = event.getFormattedMessage();
    if (rawMessage == null) {
      rawMessage = "";
    }
    String message =
        rawMessage.length() > MAX_MESSAGE_LENGTH
            ? rawMessage.substring(0, MAX_MESSAGE_LENGTH)
            : rawMessage;

    String service = config.getService();
    String env = config.getEnv();

    Map<String, String> mdc = event.getMDCPropertyMap();
    if (mdc == null) {
      mdc = Collections.emptyMap();
    }

    LogcoreEntry.Trace trace = extractTrace(mdc);
    Map<String, String> labels = extractLabels(mdc);
    Map<String, Object> context = extractContext(event);
    LogcoreEntry.Error error = extractError(event);

    String contextJson = null;
    try {
      if (context != null && !context.isEmpty()) {
        contextJson = OBJECT_MAPPER.writeValueAsString(context);
      }
    } catch (Exception ignored) {
    }

    String insertId =
        LogcoreEntry.computeInsertId(timestamp, service, severity, message, contextJson);

    return new LogcoreEntry(
        timestamp,
        severity,
        message,
        service,
        env,
        null, // source_project omitted for external backend
        insertId,
        trace,
        error,
        labels,
        context);
  }

  private LogcoreEntry.Trace extractTrace(Map<String, String> mdc) {
    String traceId = mdc.get("traceId");
    if (traceId == null) {
      traceId = mdc.get("trace_id");
    }
    String spanId = mdc.get("spanId");
    if (spanId == null) {
      spanId = mdc.get("span_id");
    }
    boolean sampled = true;

    String traceparent = mdc.get("traceparent");
    if (traceparent != null) {
      Matcher m = TRACEPARENT_PATTERN.matcher(traceparent.trim());
      if (m.matches()) {
        traceId = m.group(1).toLowerCase();
        spanId = m.group(2).toLowerCase();
        sampled = "01".equals(m.group(3));
      }
    }

    if (traceId != null && spanId != null) {
      return new LogcoreEntry.Trace(traceId, spanId, sampled);
    }
    return null;
  }

  private Map<String, String> extractLabels(Map<String, String> mdc) {
    if (mdc.isEmpty()) {
      return null;
    }
    Map<String, String> labels = new HashMap<>();
    for (Map.Entry<String, String> e : mdc.entrySet()) {
      if (labels.size() >= MAX_LABELS) {
        break;
      }
      String key = e.getKey();
      String val = e.getValue();
      if (key == null || val == null) {
        continue;
      }
      if ("traceId".equals(key)
          || "spanId".equals(key)
          || "trace_id".equals(key)
          || "span_id".equals(key)
          || "traceparent".equals(key)) {
        continue;
      }
      if (val.length() > MAX_LABEL_LENGTH) {
        val = val.substring(0, MAX_LABEL_LENGTH);
      }
      labels.put(key, val);
    }
    return labels.isEmpty() ? null : labels;
  }

  private Map<String, Object> extractContext(ILoggingEvent event) {
    Map<String, Object> context = new HashMap<>();
    if (event.getLoggerName() != null) {
      context.put("logger", event.getLoggerName());
    }
    if (event.getThreadName() != null) {
      context.put("thread", event.getThreadName());
    }
    return context.isEmpty() ? null : context;
  }

  private LogcoreEntry.Error extractError(ILoggingEvent event) {
    IThrowableProxy top = event.getThrowableProxy();
    if (top == null) {
      return null;
    }

    String type = top.getClassName();
    String message = top.getMessage() != null ? top.getMessage() : "";

    // Follow exception chain to its root cause, and put the root cause stack first
    IThrowableProxy root = top;
    while (root.getCause() != null) {
      root = root.getCause();
    }

    List<LogcoreEntry.StackFrame> stack = new ArrayList<>();
    StackTraceElementProxy[] stepArray = root.getStackTraceElementProxyArray();
    if (stepArray != null) {
      for (StackTraceElementProxy step : stepArray) {
        if (stack.size() >= MAX_STACK_FRAMES) {
          break;
        }
        StackTraceElement ste = step.getStackTraceElement();
        if (ste == null) {
          continue;
        }
        String function = ste.getMethodName();
        String file =
            ste.getFileName() != null ? ste.getFileName() : (ste.getClassName() + ".java");
        int line = ste.getLineNumber() > 0 ? ste.getLineNumber() : 1;
        boolean inApp = ste.getClassName().startsWith("io.spring");
        stack.add(new LogcoreEntry.StackFrame(function, file, line, 0, inApp));
      }
    }

    // If root cause has fewer than MAX_STACK_FRAMES and top != root, append top cause frames
    if (root != top && top.getStackTraceElementProxyArray() != null) {
      for (StackTraceElementProxy step : top.getStackTraceElementProxyArray()) {
        if (stack.size() >= MAX_STACK_FRAMES) {
          break;
        }
        StackTraceElement ste = step.getStackTraceElement();
        if (ste == null) {
          continue;
        }
        String function = ste.getMethodName();
        String file =
            ste.getFileName() != null ? ste.getFileName() : (ste.getClassName() + ".java");
        int line = ste.getLineNumber() > 0 ? ste.getLineNumber() : 1;
        boolean inApp = ste.getClassName().startsWith("io.spring");
        stack.add(new LogcoreEntry.StackFrame(function, file, line, 0, inApp));
      }
    }

    return new LogcoreEntry.Error(type, message, stack);
  }

  public static void flushAll() {
    for (LogcoreAppender appender : ACTIVE_APPENDERS) {
      if (appender.getClient() != null) {
        appender.getClient().flushBuffer();
      }
    }
  }
}
