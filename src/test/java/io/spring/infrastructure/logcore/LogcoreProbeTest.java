package io.spring.infrastructure.logcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class LogcoreProbeTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void testLogcoreAppenderAndGoldenEntrySerialization() throws Exception {
    LogcoreAppender.ensureAttached();

    LogcoreConfig config =
        new LogcoreConfig(
            "https://logcore-dev-352942961463.us-east4.run.app",
            "test-key",
            true,
            "spring-boot-realworld-example-app",
            "dev",
            "ERROR");

    LogcoreAppender appender = new LogcoreAppender(config, null);
    appender.start();

    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger logger = context.getLogger("io.spring.LogcoreProbeTest");

    MDC.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    MDC.put("userId", "12345");

    try {
      Exception cause = new IllegalArgumentException("Root cause error");
      Exception top = new RuntimeException("Top level error", cause);

      logger.error("checkout failed", top);

      LoggingEvent event =
          new LoggingEvent(
              "io.spring.LogcoreProbeTest", logger, Level.ERROR, "checkout failed", top, null);
      event.setTimeStamp(System.currentTimeMillis());
      event.setMDCPropertyMap(MDC.getCopyOfContextMap());

      LogcoreEntry entry = appender.formatEntry(event);

      assertNotNull(entry);
      assertEquals("ERROR", entry.getSeverity());
      assertEquals("checkout failed", entry.getMessage());
      assertEquals("spring-boot-realworld-example-app", entry.getService());
      assertEquals("dev", entry.getEnv());
      assertNotNull(entry.getInsertId());
      assertEquals(32, entry.getInsertId().length());

      assertNotNull(entry.getTrace());
      assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", entry.getTrace().getTraceId());
      assertEquals("00f067aa0ba902b7", entry.getTrace().getSpanId());
      assertTrue(entry.getTrace().getSampled());

      assertNotNull(entry.getError());
      assertEquals("java.lang.RuntimeException", entry.getError().getType());
      assertEquals("Top level error", entry.getError().getMessage());
      assertNotNull(entry.getError().getStack());
      assertFalse(entry.getError().getStack().isEmpty());

      // Verify JSON serialization
      String json = objectMapper.writeValueAsString(entry);
      System.out.println(json);
      JsonNode node = objectMapper.readTree(json);

      assertTrue(node.has("timestamp"));
      assertEquals("ERROR", node.get("severity").asText());
      assertEquals("checkout failed", node.get("message").asText());
      assertEquals("spring-boot-realworld-example-app", node.get("service").asText());
      assertEquals("dev", node.get("env").asText());
      assertEquals(32, node.get("insert_id").asText().length());
      assertTrue(node.has("trace"));
      assertTrue(node.has("error"));
      assertFalse(node.has("source_project")); // Must be omitted for HTTP

      // Verify envelope
      LogcoreEnvelope envelope = new LogcoreEnvelope(Collections.singletonList(entry));
      String envJson = objectMapper.writeValueAsString(envelope);
      JsonNode envNode = objectMapper.readTree(envJson);
      assertEquals(1, envNode.get("schema_version").asInt());
      assertTrue(envNode.get("entries").isArray());
    } finally {
      MDC.clear();
      appender.stop();
    }
  }

  @Test
  public void testDeterministicInsertId() {
    LogcoreAppender appender = new LogcoreAppender();
    Map<String, Object> context = new HashMap<>();
    context.put("b", "value2");
    context.put("a", "value1");

    String id1 =
        appender.computeInsertId("2026-08-17T18:30:00.000Z", "my-svc", "ERROR", "msg", context);
    String id2 =
        appender.computeInsertId("2026-08-17T18:30:00.000Z", "my-svc", "ERROR", "msg", context);
    assertEquals(id1, id2);
    assertEquals(32, id1.length());
  }

  @Test
  public void testTraceFilter() throws ServletException, IOException {
    LogcoreTraceFilter filter = new LogcoreTraceFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    MockHttpServletResponse response = new MockHttpServletResponse();

    final String[] capturedMdc = new String[3];
    FilterChain chain =
        (req, res) -> {
          capturedMdc[0] = MDC.get("traceId");
          capturedMdc[1] = MDC.get("spanId");
          capturedMdc[2] = MDC.get("traceSampled");
        };

    filter.doFilter(request, response, chain);

    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", capturedMdc[0]);
    assertEquals("00f067aa0ba902b7", capturedMdc[1]);
    assertEquals("true", capturedMdc[2]);

    // Ensure MDC was cleared
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("spanId"));
  }

  @Test
  public void testClientQueueAndClose() {
    LogcoreConfig config =
        new LogcoreConfig(
            "https://logcore-dev-352942961463.us-east4.run.app",
            null, // null key means dev mode
            true,
            "spring-boot-realworld-example-app",
            "dev",
            "ERROR");
    LogcoreClient client = new LogcoreClient(config);

    LogcoreEntry entry = new LogcoreEntry();
    entry.setMessage("test message");
    entry.setSeverity("ERROR");
    client.enqueue(entry);

    assertEquals(1, client.getBufferSize());
    client.flush();
    assertEquals(0, client.getBufferSize());

    client.close();
  }

  @Test
  public void testEmitErrorLog() {
    LogcoreAppender.ensureAttached();
    org.slf4j.Logger log = LoggerFactory.getLogger(LogcoreProbeTest.class);
    log.error(
        "Logcore probe test error event emitted successfully",
        new RuntimeException("Probe test exception"));
  }
}
