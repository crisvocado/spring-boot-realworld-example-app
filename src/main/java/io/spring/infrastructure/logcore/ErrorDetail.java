package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorDetail {

  @JsonProperty("type")
  private String type;

  @JsonProperty("message")
  private String message;

  @JsonProperty("stack")
  private List<StackFrame> stack;

  public ErrorDetail() {}

  public ErrorDetail(String type, String message, List<StackFrame> stack) {
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

  public List<StackFrame> getStack() {
    return stack;
  }

  public void setStack(List<StackFrame> stack) {
    this.stack = stack;
  }
}
