package io.spring.logcore;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogcorePayloadBuilder {

  private static final Pattern TRACEPARENT_PATTERN =
      Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
  private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
  private static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-f]{16}$");

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private final LogcoreConfig config;

  public LogcorePayloadBuilder(LogcoreConfig config) {
    this.config = config;
  }

  public Map<String, Object> buildEntry(ILoggingEvent event) {
    Map<String, Object> entry = new LinkedHashMap<>();

    String timestamp = Instant.ofEpochMilli(event.getTimeStamp()).toString();
    entry.put("timestamp", timestamp);

    String severity = normalizeSeverity(event.getLevel().toString());
    entry.put("severity", severity);

    String message = event.getFormattedMessage();
    if (message == null || message.isBlank()) {
      message = "unknown error";
    } else if (message.length() > 65536) {
      message = message.substring(0, 65536);
    }
    entry.put("message", message);

    String service = config.getService();
    entry.put("service", service);

    String env = config.getEnv();
    entry.put("env", env);

    Map<String, Object> context = extractContext(event);
    String contextJson = (context != null) ? toJsonSafe(context) : "{}";

    String insertId = computeInsertId(timestamp, service, severity, message, contextJson);
    entry.put("insert_id", insertId);

    Map<String, Object> trace = extractTrace(event);
    if (trace != null) {
      entry.put("trace", trace);
    }

    Map<String, Object> error = extractError(event);
    if (error != null) {
      entry.put("error", error);
    }

    if (context != null && !context.isEmpty()) {
      entry.put("context", context);
    }

    return entry;
  }

  public String buildEnvelopeJson(List<Map<String, Object>> entries) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schema_version", 1);
    envelope.put("entries", entries);
    return toJsonSafe(envelope);
  }

  private static String normalizeSeverity(String rawLevel) {
    switch (rawLevel) {
      case "ERROR":
        return "ERROR";
      case "WARN":
        return "WARNING";
      case "INFO":
        return "INFO";
      case "DEBUG":
        return "DEBUG";
      default:
        return "ERROR";
    }
  }

  private static Map<String, Object> extractError(ILoggingEvent event) {
    IThrowableProxy tp = event.getThrowableProxy();
    if (tp == null) {
      return null;
    }

    Map<String, Object> error = new LinkedHashMap<>();

    String type = tp.getClassName();
    if (type != null && type.length() > 256) {
      type = type.substring(0, 256);
    }
    error.put("type", (type != null && !type.isBlank()) ? type : "Error");

    String message = tp.getMessage();
    if (message == null || message.isBlank()) {
      message = (type != null) ? type : "Error";
    } else if (message.length() > 65536) {
      message = message.substring(0, 65536);
    }
    error.put("message", message);

    IThrowableProxy root = tp;
    while (root.getCause() != null) {
      root = root.getCause();
    }

    StackTraceElementProxy[] stepArray = root.getStackTraceElementProxyArray();
    if (stepArray == null || stepArray.length == 0) {
      stepArray = tp.getStackTraceElementProxyArray();
    }

    if (stepArray != null && stepArray.length > 0) {
      int maxFrames = Math.min(stepArray.length, 50);
      List<Map<String, Object>> stack = new ArrayList<>(maxFrames);
      for (int i = 0; i < maxFrames; i++) {
        StackTraceElement ste = stepArray[i].getStackTraceElement();
        if (ste == null) {
          continue;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        String function = ste.getClassName() + "." + ste.getMethodName();
        if (function.length() > 256) {
          function = function.substring(0, 256);
        }
        frame.put("function", function);

        String file = ste.getFileName();
        if (file != null) {
          if (file.length() > 1024) {
            file = file.substring(0, 1024);
          }
          frame.put("file", file);
        }

        int line = ste.getLineNumber();
        if (line > 0) {
          frame.put("line", line);
        }

        boolean inApp = ste.getClassName().startsWith("io.spring");
        frame.put("inApp", inApp);

        stack.add(frame);
      }
      error.put("stack", stack);
    }

    return error;
  }

  private static Map<String, Object> extractTrace(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    if (mdc == null || mdc.isEmpty()) {
      return null;
    }

    String traceparent = mdc.get("traceparent");
    if (traceparent != null) {
      Matcher m = TRACEPARENT_PATTERN.matcher(traceparent.trim().toLowerCase());
      if (m.matches()) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", m.group(1));
        trace.put("spanId", m.group(2));
        trace.put("sampled", "01".equals(m.group(3)));
        return trace;
      }
    }

    String traceId = mdc.get("traceId");
    if (traceId == null) {
      traceId = mdc.get("trace_id");
    }
    if (traceId == null) {
      traceId = mdc.get("X-B3-TraceId");
    }

    if (traceId != null) {
      traceId = traceId.trim().toLowerCase();
      if (TRACE_ID_PATTERN.matcher(traceId).matches()) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", traceId);

        String spanId = mdc.get("spanId");
        if (spanId == null) {
          spanId = mdc.get("span_id");
        }
        if (spanId == null) {
          spanId = mdc.get("X-B3-SpanId");
        }
        if (spanId != null) {
          spanId = spanId.trim().toLowerCase();
          if (SPAN_ID_PATTERN.matcher(spanId).matches()) {
            trace.put("spanId", spanId);
          }
        }
        trace.put("sampled", true);
        return trace;
      }
    }

    return null;
  }

  private static Map<String, Object> extractContext(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    if (mdc == null || mdc.isEmpty()) {
      return null;
    }

    Map<String, Object> context = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : mdc.entrySet()) {
      String key = entry.getKey();
      if ("traceparent".equalsIgnoreCase(key)
          || "traceId".equalsIgnoreCase(key)
          || "trace_id".equalsIgnoreCase(key)
          || "spanId".equalsIgnoreCase(key)
          || "span_id".equalsIgnoreCase(key)
          || "X-B3-TraceId".equalsIgnoreCase(key)
          || "X-B3-SpanId".equalsIgnoreCase(key)) {
        continue;
      }
      context.put(key, entry.getValue());
    }

    return context.isEmpty() ? null : context;
  }

  private static String computeInsertId(
      String timestamp, String service, String severity, String message, String contextJson) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String raw =
          timestamp + "|" + service + "|" + severity + "|" + message + "|" + contextJson;
      byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(32);
      for (int i = 0; i < 16; i++) {
        hex.append(String.format("%02x", hash[i]));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm unavailable", e);
    }
  }

  private static String toJsonSafe(Object obj) {
    try {
      return OBJECT_MAPPER.writeValueAsString(obj);
    } catch (Exception e) {
      return "{}";
    }
  }
}
