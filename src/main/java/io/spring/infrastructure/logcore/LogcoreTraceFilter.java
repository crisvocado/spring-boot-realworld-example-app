package io.spring.infrastructure.logcore;

import java.io.IOException;
import java.util.UUID;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LogcoreTraceFilter extends OncePerRequestFilter {

  public static final String TRACEPARENT_HEADER = "traceparent";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String traceparent = request.getHeader(TRACEPARENT_HEADER);
    String traceId;
    String spanId;
    boolean sampled = true;

    if (traceparent != null && !traceparent.trim().isEmpty()) {
      String[] parts = traceparent.trim().split("-");
      if (parts.length >= 4) {
        traceId = parts[1];
        spanId = parts[2];
        sampled = "01".equals(parts[3]) || parts[3].endsWith("1");
      } else {
        traceId = generateHex(32);
        spanId = generateHex(16);
      }
    } else {
      String b3TraceId = request.getHeader("X-B3-TraceId");
      String b3SpanId = request.getHeader("X-B3-SpanId");
      if (b3TraceId != null && b3SpanId != null) {
        traceId = b3TraceId;
        spanId = b3SpanId;
      } else {
        traceId = generateHex(32);
        spanId = generateHex(16);
      }
      traceparent = String.format("00-%s-%s-01", traceId, spanId);
    }

    try {
      MDC.put("traceparent", traceparent);
      MDC.put("traceId", traceId);
      MDC.put("spanId", spanId);
      MDC.put("traceSampled", String.valueOf(sampled));
      response.setHeader(TRACEPARENT_HEADER, traceparent);

      filterChain.doFilter(request, response);
    } finally {
      MDC.remove("traceparent");
      MDC.remove("traceId");
      MDC.remove("spanId");
      MDC.remove("traceSampled");
    }
  }

  private static String generateHex(int length) {
    String uuid =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    return uuid.substring(0, length);
  }
}
