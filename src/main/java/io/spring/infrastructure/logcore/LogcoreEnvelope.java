package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"schema_version", "entries"})
public class LogcoreEnvelope {

  @JsonProperty("schema_version")
  private int schemaVersion = 1;

  @JsonProperty("entries")
  private List<LogcoreEntry> entries = new ArrayList<>();

  public LogcoreEnvelope() {
    this.schemaVersion = 1;
  }

  public LogcoreEnvelope(List<LogcoreEntry> entries) {
    this.schemaVersion = 1;
    this.entries = entries != null ? entries : new ArrayList<>();
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(int schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public List<LogcoreEntry> getEntries() {
    return entries;
  }

  public void setEntries(List<LogcoreEntry> entries) {
    this.entries = entries;
  }
}
