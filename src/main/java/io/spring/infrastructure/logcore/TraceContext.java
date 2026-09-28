package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceContext {

  @JsonProperty("traceId")
  private String traceId;

  @JsonProperty("spanId")
  private String spanId;

  @JsonProperty("sampled")
  private Boolean sampled;

  public TraceContext() {}

  public TraceContext(String traceId, String spanId, Boolean sampled) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.sampled = sampled;
  }

  public String getTraceId() {
    return traceId;
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public void setSpanId(String spanId) {
    this.spanId = spanId;
  }

  public Boolean getSampled() {
    return sampled;
  }

  public void setSampled(Boolean sampled) {
    this.sampled = sampled;
  }
}
