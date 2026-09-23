package io.spring.logcore;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogcoreEntry {

  @JsonProperty("timestamp")
  private final String timestamp;

  @JsonProperty("severity")
  private final String severity;

  @JsonProperty("message")
  private final String message;

  @JsonProperty("service")
  private final String service;

  @JsonProperty("env")
  private final String env;

  @JsonProperty("source_project")
  private final String sourceProject;

  @JsonProperty("insert_id")
  private final String insertId;

  @JsonProperty("trace")
  private final Trace trace;

  @JsonProperty("error")
  private final Error error;

  @JsonProperty("labels")
  private final Map<String, String> labels;

  @JsonProperty("context")
  private final Map<String, Object> context;

  public LogcoreEntry(
      String timestamp,
      String severity,
      String message,
      String service,
      String env,
      String sourceProject,
      String insertId,
      Trace trace,
      Error error,
      Map<String, String> labels,
      Map<String, Object> context) {
    this.timestamp = timestamp;
    this.severity = severity;
    this.message = message;
    this.service = service;
    this.env = env;
    this.sourceProject = sourceProject;
    this.insertId = insertId;
    this.trace = trace;
    this.error = error;
    this.labels = labels;
    this.context = context;
  }

  public String getTimestamp() {
    return timestamp;
  }

  public String getSeverity() {
    return severity;
  }

  public String getMessage() {
    return message;
  }

  public String getService() {
    return service;
  }

  public String getEnv() {
    return env;
  }

  public String getSourceProject() {
    return sourceProject;
  }

  public String getInsertId() {
    return insertId;
  }

  public Trace getTrace() {
    return trace;
  }

  public Error getError() {
    return error;
  }

  public Map<String, String> getLabels() {
    return labels;
  }

  public Map<String, Object> getContext() {
    return context;
  }

  public static String computeInsertId(
      String timestamp, String service, String severity, String message, String contextJson) {
    String input =
        (timestamp != null ? timestamp : "")
            + "|"
            + (service != null ? service : "")
            + "|"
            + (severity != null ? severity : "")
            + "|"
            + (message != null ? message : "")
            + "|"
            + (contextJson != null ? contextJson : "{}");
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Trace {
    @JsonProperty("traceId")
    private final String traceId;

    @JsonProperty("spanId")
    private final String spanId;

    @JsonProperty("sampled")
    private final boolean sampled;

    public Trace(String traceId, String spanId, boolean sampled) {
      this.traceId = traceId;
      this.spanId = spanId;
      this.sampled = sampled;
    }

    public String getTraceId() {
      return traceId;
    }

    public String getSpanId() {
      return spanId;
    }

    public boolean isSampled() {
      return sampled;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Error {
    @JsonProperty("type")
    private final String type;

    @JsonProperty("message")
    private final String message;

    @JsonProperty("stack")
    private final List<StackFrame> stack;

    public Error(String type, String message, List<StackFrame> stack) {
      this.type = type;
      this.message = message;
      this.stack = stack;
    }

    public String getType() {
      return type;
    }

    public String getMessage() {
      return message;
    }

    public List<StackFrame> getStack() {
      return stack;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class StackFrame {
    @JsonProperty("function")
    private final String function;

    @JsonProperty("file")
    private final String file;

    @JsonProperty("line")
    private final int line;

    @JsonProperty("column")
    private final int column;

    @JsonProperty("inApp")
    private final boolean inApp;

    public StackFrame(String function, String file, int line, int column, boolean inApp) {
      this.function = function;
      this.file = file;
      this.line = line;
      this.column = column;
      this.inApp = inApp;
    }

    public String getFunction() {
      return function;
    }

    public String getFile() {
      return file;
    }

    public int getLine() {
      return line;
    }

    public int getColumn() {
      return column;
    }

    public boolean isInApp() {
      return inApp;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Envelope {
    @JsonProperty("schema_version")
    private final int schemaVersion = 1;

    @JsonProperty("entries")
    private final List<LogcoreEntry> entries;

    public Envelope(List<LogcoreEntry> entries) {
      this.entries = entries;
    }

    public int getSchemaVersion() {
      return schemaVersion;
    }

    public List<LogcoreEntry> getEntries() {
      return entries;
    }
  }
}
