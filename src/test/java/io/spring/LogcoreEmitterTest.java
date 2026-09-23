package io.spring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.logcore.LogcoreAppender;
import io.spring.logcore.LogcoreConfig;
import io.spring.logcore.LogcoreEntry;
import io.spring.logcore.LogcoreProbe;
import io.spring.logcore.LogcoreTraceFilter;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class LogcoreEmitterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void testLogcoreAppenderEmitsError() throws Exception {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    LogcoreAppender appender = new LogcoreAppender();
    LogcoreConfig config =
        new LogcoreConfig(
            "https://logcore-dev-352942961463.us-east4.run.app",
            "test-key",
            true,
            "spring-boot-realworld-example-app",
            "test",
            "ERROR");
    appender.setConfig(config);
    appender.setContext(context);
    appender.start();

    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.setContext(context);
    listAppender.start();
    rootLogger.addAppender(listAppender);

    MDC.put("custom_label", "value123");
    MDC.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    org.slf4j.Logger logger = LoggerFactory.getLogger(LogcoreEmitterTest.class);
    Exception rootCause = new IllegalArgumentException("Root cause error");
    Exception topException = new RuntimeException("Wrapped service failure", rootCause);

    logger.error("checkout failed", topException);

    assertFalse(listAppender.list.isEmpty());
    ILoggingEvent lastEvent = listAppender.list.get(listAppender.list.size() - 1);
    assertEquals("checkout failed", lastEvent.getMessage());

    LogcoreEntry entry = appender.buildEntry(lastEvent);
    assertNotNull(entry);
    assertEquals("ERROR", entry.getSeverity());
    assertEquals("checkout failed", entry.getMessage());
    assertEquals("spring-boot-realworld-example-app", entry.getService());
    assertEquals("test", entry.getEnv());
    assertNull(entry.getSourceProject());

    // Verify insert_id is 32-hex characters
    assertNotNull(entry.getInsertId());
    assertTrue(
        entry.getInsertId().matches("^[0-9a-f]{32}$"),
        "insert_id must be 32 hex chars: " + entry.getInsertId());

    // Verify trace
    assertNotNull(entry.getTrace());
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", entry.getTrace().getTraceId());
    assertEquals("00f067aa0ba902b7", entry.getTrace().getSpanId());
    assertTrue(entry.getTrace().isSampled());

    // Verify error and stack
    assertNotNull(entry.getError());
    assertEquals("java.lang.RuntimeException", entry.getError().getType());
    assertEquals("Wrapped service failure", entry.getError().getMessage());
    assertNotNull(entry.getError().getStack());
    assertFalse(entry.getError().getStack().isEmpty());

    // Root cause frame is innermost at index 0
    LogcoreEntry.StackFrame firstFrame = entry.getError().getStack().get(0);
    assertNotNull(firstFrame.getFunction());
    assertTrue(firstFrame.isInApp());

    // Verify labels
    assertNotNull(entry.getLabels());
    assertEquals("value123", entry.getLabels().get("custom_label"));

    // Verify context
    assertNotNull(entry.getContext());
    assertEquals(LogcoreEmitterTest.class.getName(), entry.getContext().get("logger"));

    // Verify JSON envelope serialization
    LogcoreEntry.Envelope envelope =
        new LogcoreEntry.Envelope(Collections.singletonList(entry));
    String json = objectMapper.writeValueAsString(envelope);
    JsonNode root = objectMapper.readTree(json);

    assertEquals(1, root.get("schema_version").asInt());
    assertTrue(root.get("entries").isArray());
    assertEquals(1, root.get("entries").size());

    JsonNode entryNode = root.get("entries").get(0);
    assertEquals("ERROR", entryNode.get("severity").asText());
    assertEquals("checkout failed", entryNode.get("message").asText());
    assertEquals("spring-boot-realworld-example-app", entryNode.get("service").asText());
    assertEquals("test", entryNode.get("env").asText());
    assertEquals(entry.getInsertId(), entryNode.get("insert_id").asText());
    assertFalse(entryNode.has("source_project"));

    // Print JSON entry to stdout for logcore validation
    System.out.println(objectMapper.writeValueAsString(entry));

    appender.stop();
    listAppender.stop();
    rootLogger.detachAppender(listAppender);
    MDC.clear();
  }

  @Test
  public void testTraceFilterBindsTraceparent() throws Exception {
    LogcoreTraceFilter filter = new LogcoreTraceFilter();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(
        "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              javax.servlet.ServletRequest req, javax.servlet.ServletResponse res) {
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MDC.get("traceId"));
            assertEquals("00f067aa0ba902b7", MDC.get("spanId"));
            assertEquals(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                MDC.get("traceparent"));
          }
        };

    filter.doFilter(request, response, chain);

    assertEquals(
        "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        response.getHeader("traceparent"));
    assertNull(MDC.get("traceId"));
    assertNull(MDC.get("spanId"));
    assertNull(MDC.get("traceparent"));
  }

  @Test
  public void testLogcoreProbeRunsWithoutException() {
    assertDoesNotThrow(LogcoreProbe::emitTestError);
  }
}
