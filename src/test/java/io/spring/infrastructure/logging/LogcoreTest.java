package io.spring.infrastructure.logging;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class LogcoreTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private static final Pattern INSERT_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
  private static final Pattern SERVICE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");

  @BeforeEach
  public void setUp() {
    MDC.clear();
  }

  @Test
  public void should_build_valid_logcore_entry_matching_wire_shape() throws Exception {
    LogcoreConfig config = new LogcoreConfig();
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger logger = lc.getLogger("io.spring.test.SampleService");

    Exception rootCause = new IllegalArgumentException("Invalid user ID");
    Exception topException = new RuntimeException("Operation failed", rootCause);

    LoggingEvent event =
        new LoggingEvent(
            "io.spring.test.SampleService",
            logger,
            Level.ERROR,
            "Failed to process order",
            topException,
            new Object[0]);

    Map<String, Object> entry = LogcoreAppender.buildEntry(event, config);

    assertNotNull(entry.get("timestamp"));
    assertEquals("ERROR", entry.get("severity"));
    assertEquals("Failed to process order", entry.get("message"));
    assertEquals(config.getService(), entry.get("service"));
    assertTrue(SERVICE_PATTERN.matcher((String) entry.get("service")).matches());
    assertEquals("dev", entry.get("env"));

    String insertId = (String) entry.get("insert_id");
    assertNotNull(insertId);
    assertEquals(32, insertId.length());
    assertTrue(INSERT_ID_PATTERN.matcher(insertId).matches());

    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) entry.get("error");
    assertNotNull(error);
    assertEquals("java.lang.RuntimeException", error.get("type"));
    assertEquals("Operation failed", error.get("message"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> stack = (List<Map<String, Object>>) error.get("stack");
    assertNotNull(stack);
    assertFalse(stack.isEmpty());

    Map<String, Object> firstFrame = stack.get(0);
    assertNotNull(firstFrame.get("function"));
    assertNotNull(firstFrame.get("file"));
    assertNotNull(firstFrame.get("line"));
    assertEquals(0, firstFrame.get("column"));
    assertNotNull(firstFrame.get("inApp"));

    // Verify serialization round-trip to JSON
    String json = objectMapper.writeValueAsString(entry);
    JsonNode node = objectMapper.readTree(json);
    assertEquals("ERROR", node.get("severity").asText());
    assertEquals(32, node.get("insert_id").asText().length());
  }

  @Test
  public void should_handle_trace_and_mdc_context() throws Exception {
    LogcoreConfig config = new LogcoreConfig();
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger logger = lc.getLogger("io.spring.api.ArticlesApi");

    MDC.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    MDC.put("userId", "usr_12345");

    LoggingEvent event =
        new LoggingEvent(
            "io.spring.api.ArticlesApi",
            logger,
            Level.ERROR,
            "Article creation error",
            null,
            new Object[0]);

    Map<String, Object> entry = LogcoreAppender.buildEntry(event, config);

    @SuppressWarnings("unchecked")
    Map<String, Object> trace = (Map<String, Object>) entry.get("trace");
    assertNotNull(trace);
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.get("traceId"));
    assertEquals("00f067aa0ba902b7", trace.get("spanId"));
    assertEquals(true, trace.get("sampled"));

    @SuppressWarnings("unchecked")
    Map<String, Object> context = (Map<String, Object>) entry.get("context");
    assertNotNull(context);
    assertEquals("usr_12345", context.get("userId"));
  }

  @Test
  public void should_enqueue_and_format_batch_envelope() throws Exception {
    LogcoreConfig config = new LogcoreConfig();
    LogcoreClient client = new LogcoreClient(config);

    AtomicReference<String> capturedPayload = new AtomicReference<>();
    client.setCustomSender(capturedPayload::set);

    Map<String, Object> entry = new HashMap<>();
    entry.put("timestamp", "2026-08-17T18:30:00.000Z");
    entry.put("severity", "ERROR");
    entry.put("message", "checkout failed");
    entry.put("service", "spring-boot-realworld-example-app");
    entry.put("env", "dev");
    entry.put("insert_id", "7c2e1a0b9d8f6e4c2a0b8d6f4e2c0a8b");

    client.enqueue(entry);
    client.flushSync(1000);

    String payload = capturedPayload.get();
    assertNotNull(payload, "Payload should be captured");

    JsonNode rootNode = objectMapper.readTree(payload);
    assertEquals(1, rootNode.get("schema_version").asInt());
    assertTrue(rootNode.has("entries"));
    assertEquals(1, rootNode.get("entries").size());
    assertEquals("checkout failed", rootNode.get("entries").get(0).get("message").asText());

    client.close();
  }

  @Test
  public void should_emit_error_log_via_integrated_logger() {
    Logger log = LoggerFactory.getLogger(LogcoreTest.class);
    log.error("Logcore integration test ERROR probe", new IllegalStateException("Probe error"));
  }

  @Test
  public void should_execute_probe_successfully() {
    assertDoesNotThrow(() -> LogcoreProbe.main(new String[0]));
  }
}
