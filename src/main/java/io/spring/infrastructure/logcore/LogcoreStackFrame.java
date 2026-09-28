package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"function", "file", "line", "column", "inApp"})
public class LogcoreStackFrame {

  @JsonProperty("function")
  private String function;

  @JsonProperty("file")
  private String file;

  @JsonProperty("line")
  private int line;

  @JsonProperty("column")
  private int column;

  @JsonProperty("inApp")
  private boolean inApp;

  public LogcoreStackFrame() {}

  public LogcoreStackFrame(String function, String file, int line, int column, boolean inApp) {
    this.function = function;
    this.file = file;
    this.line = line;
    this.column = column;
    this.inApp = inApp;
  }

  public String getFunction() {
    return function;
  }

  public void setFunction(String function) {
    this.function = function;
  }

  public String getFile() {
    return file;
  }

  public void setFile(String file) {
    this.file = file;
  }

  public int getLine() {
    return line;
  }

  public void setLine(int line) {
    this.line = line;
  }

  public int getColumn() {
    return column;
  }

  public void setColumn(int column) {
    this.column = column;
  }

  public boolean isInApp() {
    return inApp;
  }

  public void setInApp(boolean inApp) {
    this.inApp = inApp;
  }
}
