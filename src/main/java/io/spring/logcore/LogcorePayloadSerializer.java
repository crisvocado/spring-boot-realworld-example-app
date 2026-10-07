package io.spring.logcore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogcorePayloadSerializer {

  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private static final Pattern TRACEPARENT_PATTERN =
      Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
  private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
  private static final Pattern SPAN_ID_PATTERN = Pattern.compile("^[0-9a-f]{16}$");
  private static final Pattern LABEL_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  public static Map<String, Object> serializeEvent(ILoggingEvent event, LogcoreConfig config) {
    Map<String, Object> entry = new LinkedHashMap<>();

    String timestamp = ISO_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp()));
    entry.put("timestamp", timestamp);

    String severity = mapSeverity(event.getLevel());
    entry.put("severity", severity);

    String message = event.getFormattedMessage();
    if (message == null || message.trim().isEmpty()) {
      if (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage() != null) {
        message = event.getThrowableProxy().getMessage();
      } else {
        message = "Unknown error";
      }
    }
    if (message.length() > 65536) {
      message = message.substring(0, 65536);
    }
    entry.put("message", message);

    String service = config.getService();
    entry.put("service", service);

    String env = config.getEnv();
    entry.put("env", env);

    Map<String, String> mdc = event.getMDCPropertyMap();
    Map<String, Object> trace = extractTrace(mdc);
    if (trace != null) {
      entry.put("trace", trace);
    }

    Map<String, Object> error = extractError(event.getThrowableProxy());
    if (error != null) {
      entry.put("error", error);
    }

    Map<String, String> labels = extractLabels(event, mdc);
    if (labels != null && !labels.isEmpty()) {
      entry.put("labels", labels);
    }

    String insertId = computeInsertId(timestamp, service, severity, message, null);
    entry.put("insert_id", insertId);

    return entry;
  }

  public static String serializeEnvelope(List<Map<String, Object>> entries)
      throws JsonProcessingException {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schema_version", 1);
    envelope.put("entries", entries);
    return OBJECT_MAPPER.writeValueAsString(envelope);
  }

  private static String mapSeverity(Level level) {
    if (level == null) {
      return "ERROR";
    }
    if (level.toInt() >= Level.ERROR_INT) {
      return "ERROR";
    } else if (level.toInt() >= Level.WARN_INT) {
      return "WARNING";
    } else if (level.toInt() >= Level.INFO_INT) {
      return "INFO";
    } else {
      return "DEBUG";
    }
  }

  private static Map<String, Object> extractTrace(Map<String, String> mdc) {
    if (mdc == null || mdc.isEmpty()) {
      return null;
    }

    String traceparent = mdc.get("traceparent");
    if (traceparent == null) {
      traceparent = mdc.get("w3c_traceparent");
    }
    if (traceparent != null) {
      Matcher m = TRACEPARENT_PATTERN.matcher(traceparent.trim().toLowerCase());
      if (m.matches()) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", m.group(1));
        trace.put("spanId", m.group(2));
        int flags = Integer.parseInt(m.group(3), 16);
        trace.put("sampled", (flags & 1) != 0);
        return trace;
      }
    }

    String traceId = mdc.get("traceId");
    if (traceId == null) {
      traceId = mdc.get("trace_id");
    }
    if (traceId != null) {
      String cleanTraceId = traceId.trim().toLowerCase();
      if (TRACE_ID_PATTERN.matcher(cleanTraceId).matches()) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", cleanTraceId);

        String spanId = mdc.get("spanId");
        if (spanId == null) {
          spanId = mdc.get("span_id");
        }
        if (spanId != null) {
          String cleanSpanId = spanId.trim().toLowerCase();
          if (SPAN_ID_PATTERN.matcher(cleanSpanId).matches()) {
            trace.put("spanId", cleanSpanId);
          }
        }

        String sampledStr = mdc.get("sampled");
        if (sampledStr != null) {
          trace.put("sampled", Boolean.parseBoolean(sampledStr));
        }
        return trace;
      }
    }

    return null;
  }

  private static Map<String, Object> extractError(IThrowableProxy tp) {
    if (tp == null) {
      return null;
    }

    Map<String, Object> error = new LinkedHashMap<>();

    String type = tp.getClassName();
    if (type == null || type.isEmpty()) {
      type = "Exception";
    }
    if (type.length() > 256) {
      type = type.substring(0, 256);
    }
    error.put("type", type);

    String message = tp.getMessage();
    if (message == null) {
      message = type;
    }
    if (message.length() > 65536) {
      message = message.substring(0, 65536);
    }
    error.put("message", message);

    // Follow cause chain to root cause first
    IThrowableProxy root = tp;
    while (root.getCause() != null) {
      root = root.getCause();
    }

    List<Map<String, Object>> stack = new ArrayList<>();
    StackTraceElementProxy[] stepArray = root.getStackTraceElementProxyArray();
    if (stepArray != null) {
      for (int i = 0; i < stepArray.length && stack.size() < 50; i++) {
        StackTraceElement ste = stepArray[i].getStackTraceElement();
        if (ste != null) {
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
          if (line >= 0) {
            frame.put("line", line);
          }

          boolean inApp = ste.getClassName().startsWith("io.spring.");
          frame.put("inApp", inApp);

          stack.add(frame);
        }
      }
    }

    error.put("stack", stack);
    return error;
  }

  private static Map<String, String> extractLabels(
      ILoggingEvent event, Map<String, String> mdc) {
    Map<String, String> labels = new LinkedHashMap<>();

    if (event.getLoggerName() != null) {
      String logger = event.getLoggerName();
      if (logger.length() > 1024) {
        logger = logger.substring(0, 1024);
      }
      labels.put("logger", logger);
    }

    if (event.getThreadName() != null) {
      String thread = event.getThreadName();
      if (thread.length() > 1024) {
        thread = thread.substring(0, 1024);
      }
      labels.put("thread", thread);
    }

    if (mdc != null) {
      for (Map.Entry<String, String> entry : mdc.entrySet()) {
        if (labels.size() >= 32) {
          break;
        }
        String key = entry.getKey();
        String val = entry.getValue();
        if (key != null && val != null && LABEL_KEY_PATTERN.matcher(key).matches()) {
          if (!"traceparent".equalsIgnoreCase(key)
              && !"traceid".equalsIgnoreCase(key)
              && !"trace_id".equalsIgnoreCase(key)
              && !"spanid".equalsIgnoreCase(key)
              && !"span_id".equalsIgnoreCase(key)) {
            String valueStr = val.length() > 1024 ? val.substring(0, 1024) : val;
            labels.put(key, valueStr);
          }
        }
      }
    }

    return labels.isEmpty() ? null : labels;
  }

  private static String computeInsertId(
      String timestamp, String service, String severity, String message, String contextJson) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String payload =
          timestamp
              + "|"
              + service
              + "|"
              + severity
              + "|"
              + message
              + "|"
              + (contextJson != null ? contextJson : "");
      byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.substring(0, 32);
    } catch (Exception e) {
      return UUID.nameUUIDFromBytes(
              (timestamp + service + severity + message).getBytes(StandardCharsets.UTF_8))
          .toString()
          .replace("-", "");
    }
  }
}
