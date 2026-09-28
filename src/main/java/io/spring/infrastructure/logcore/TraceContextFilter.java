package io.spring.infrastructure.logcore;

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
public class TraceContextFilter extends OncePerRequestFilter {

  public static final String HEADER_TRACEPARENT = "traceparent";
  public static final String MDC_TRACE_ID = "traceId";
  public static final String MDC_SPAN_ID = "spanId";
  public static final String MDC_SAMPLED = "sampled";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String traceparentHeader = request.getHeader(HEADER_TRACEPARENT);
    TraceContext context = TraceContext.fromHeader(traceparentHeader);

    TraceContextHolder.set(context);
    MDC.put(MDC_TRACE_ID, context.getTraceId());
    MDC.put(MDC_SPAN_ID, context.getSpanId());
    MDC.put(MDC_SAMPLED, String.valueOf(context.isSampled()));

    response.setHeader(HEADER_TRACEPARENT, context.toTraceparentHeader());

    try {
      filterChain.doFilter(request, response);
    } finally {
      TraceContextHolder.clear();
      MDC.remove(MDC_TRACE_ID);
      MDC.remove(MDC_SPAN_ID);
      MDC.remove(MDC_SAMPLED);
    }
  }
}
