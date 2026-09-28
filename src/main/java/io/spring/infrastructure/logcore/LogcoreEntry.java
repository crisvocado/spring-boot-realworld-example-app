package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
  "timestamp",
  "severity",
  "message",
  "service",
  "env",
  "source_project",
  "insert_id",
  "trace",
  "error",
  "labels",
  "context"
})
public class LogcoreEntry {

  @JsonProperty("timestamp")
  private String timestamp;

  @JsonProperty("severity")
  private String severity;

  @JsonProperty("message")
  private String message;

  @JsonProperty("service")
  private String service;

  @JsonProperty("env")
  private String env;

  @JsonProperty("source_project")
  private String sourceProject;

  @JsonProperty("insert_id")
  private String insertId;

  @JsonProperty("trace")
  private LogcoreTrace trace;

  @JsonProperty("error")
  private LogcoreError error;

  @JsonProperty("labels")
  private Map<String, String> labels;

  @JsonProperty("context")
  private Map<String, Object> context;

  public LogcoreEntry() {}

  public String getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(String timestamp) {
    this.timestamp = timestamp;
  }

  public String getSeverity() {
    return severity;
  }

  public void setSeverity(String severity) {
    this.severity = severity;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getService() {
    return service;
  }

  public void setService(String service) {
    this.service = service;
  }

  public String getEnv() {
    return env;
  }

  public void setEnv(String env) {
    this.env = env;
  }

  public String getSourceProject() {
    return sourceProject;
  }

  public void setSourceProject(String sourceProject) {
    this.sourceProject = sourceProject;
  }

  public String getInsertId() {
    return insertId;
  }

  public void setInsertId(String insertId) {
    this.insertId = insertId;
  }

  public LogcoreTrace getTrace() {
    return trace;
  }

  public void setTrace(LogcoreTrace trace) {
    this.trace = trace;
  }

  public LogcoreError getError() {
    return error;
  }

  public void setError(LogcoreError error) {
    this.error = error;
  }

  public Map<String, String> getLabels() {
    return labels;
  }

  public void setLabels(Map<String, String> labels) {
    this.labels = labels;
  }

  public Map<String, Object> getContext() {
    return context;
  }

  public void setContext(Map<String, Object> context) {
    this.context = context;
  }
}
