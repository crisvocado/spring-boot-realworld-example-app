package io.spring.logcore;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LogcoreTraceFilter implements Filter {
  public static final String TRACEPARENT_HEADER = "traceparent";
  private static final Pattern TRACEPARENT_PATTERN =
      Pattern.compile("^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");
  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String traceId = null;
    String spanId = null;
    boolean sampled = true;

    if (request instanceof HttpServletRequest) {
      HttpServletRequest httpRequest = (HttpServletRequest) request;
      String header = httpRequest.getHeader(TRACEPARENT_HEADER);
      if (header != null && !header.trim().isEmpty()) {
        Matcher m = TRACEPARENT_PATTERN.matcher(header.trim());
        if (m.matches()) {
          traceId = m.group(1).toLowerCase();
          spanId = m.group(2).toLowerCase();
          sampled = !"00".equals(m.group(3));
        }
      }
    }

    if (traceId == null) {
      traceId = generateHex(16);
      spanId = generateHex(8);
    }

    String traceparent = "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");

    if (response instanceof HttpServletResponse) {
      ((HttpServletResponse) response).setHeader(TRACEPARENT_HEADER, traceparent);
    }

    MDC.put("traceId", traceId);
    MDC.put("spanId", spanId);
    MDC.put("traceparent", traceparent);

    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("traceId");
      MDC.remove("spanId");
      MDC.remove("traceparent");
    }
  }

  private static String generateHex(int byteCount) {
    byte[] bytes = new byte[byteCount];
    RANDOM.nextBytes(bytes);
    StringBuilder sb = new StringBuilder(byteCount * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
