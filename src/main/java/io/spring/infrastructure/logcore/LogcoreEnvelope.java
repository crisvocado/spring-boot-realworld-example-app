package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class LogcoreEnvelope {

  @JsonProperty("schema_version")
  private int schemaVersion = 1;

  @JsonProperty("entries")
  private List<LogcoreEntry> entries;

  public LogcoreEnvelope() {}

  public LogcoreEnvelope(List<LogcoreEntry> entries) {
    this.schemaVersion = 1;
    this.entries = entries;
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
