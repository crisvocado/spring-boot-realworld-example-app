package io.spring.infrastructure.logging;

import java.io.IOException;
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

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceparent = request.getHeader("traceparent");
    String traceId = null;
    String spanId = null;
    String sampled = null;

    if (traceparent != null && !traceparent.trim().isEmpty()) {
      String[] parts = traceparent.trim().split("-");
      if (parts.length >= 4) {
        traceId = parts[1];
        spanId = parts[2];
        sampled = ("01".equals(parts[3]) || parts[3].endsWith("1")) ? "true" : "false";
      }
    }

    if (traceId == null) {
      String customTrace = request.getHeader("x-trace-id");
      if (customTrace == null) {
        customTrace = request.getHeader("x-b3-traceid");
      }
      if (customTrace != null && !customTrace.trim().isEmpty()) {
        traceId = customTrace.trim();
        String customSpan = request.getHeader("x-span-id");
        if (customSpan == null) {
          customSpan = request.getHeader("x-b3-spanid");
        }
        if (customSpan != null && !customSpan.trim().isEmpty()) {
          spanId = customSpan.trim();
        }
      }
    }

    boolean setMdc = false;
    try {
      if (traceId != null) {
        MDC.put("traceId", traceId);
        setMdc = true;
      }
      if (spanId != null) {
        MDC.put("spanId", spanId);
      }
      if (sampled != null) {
        MDC.put("sampled", sampled);
      }
      filterChain.doFilter(request, response);
    } finally {
      if (setMdc) {
        MDC.remove("traceId");
        MDC.remove("spanId");
        MDC.remove("sampled");
      }
    }
  }
}
