package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "message", "stack"})
public class LogcoreError {

  @JsonProperty("type")
  private String type;

  @JsonProperty("message")
  private String message;

  @JsonProperty("stack")
  private List<LogcoreStackFrame> stack;

  public LogcoreError() {}

  public LogcoreError(String type, String message, List<LogcoreStackFrame> stack) {
    this.type = type;
    this.message = message;
    this.stack = stack;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public List<LogcoreStackFrame> getStack() {
    return stack;
  }

  public void setStack(List<LogcoreStackFrame> stack) {
    this.stack = stack;
  }
}
