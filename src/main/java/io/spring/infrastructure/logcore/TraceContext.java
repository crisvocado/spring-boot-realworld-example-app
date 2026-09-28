package io.spring.infrastructure.logcore;

import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TraceContext {

  private static final Pattern TRACEPARENT_PATTERN =
      Pattern.compile("^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");

  private static final SecureRandom RANDOM = new SecureRandom();

  private final String traceId;
  private final String spanId;
  private final boolean sampled;

  public TraceContext(String traceId, String spanId, boolean sampled) {
    this.traceId = traceId != null ? traceId.toLowerCase() : generateHex(32);
    this.spanId = spanId != null ? spanId.toLowerCase() : generateHex(16);
    this.sampled = sampled;
  }

  public static TraceContext fromHeader(String header) {
    if (header != null && !header.trim().isEmpty()) {
      Matcher matcher = TRACEPARENT_PATTERN.matcher(header.trim());
      if (matcher.matches()) {
        String traceId = matcher.group(1).toLowerCase();
        String spanId = matcher.group(2).toLowerCase();
        int flags = Integer.parseInt(matcher.group(3), 16);
        boolean sampled = (flags & 1) == 1;
        return new TraceContext(traceId, spanId, sampled);
      }
    }
    return generate();
  }

  public static TraceContext generate() {
    return new TraceContext(generateHex(32), generateHex(16), true);
  }

  public String toTraceparentHeader() {
    return String.format("00-%s-%s-%s", traceId, spanId, sampled ? "01" : "00");
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

  private static String generateHex(int length) {
    byte[] bytes = new byte[length / 2];
    RANDOM.nextBytes(bytes);
    StringBuilder sb = new StringBuilder(length);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
