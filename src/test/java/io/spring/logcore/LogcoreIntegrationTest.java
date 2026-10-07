package io.spring.logcore;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class LogcoreIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  public void setUp() {
    LogcoreAppender.install();
  }

  @AfterEach
  public void tearDown() {
    LogcoreHttpTransport.getInstance().setCustomSender(null);
    MDC.clear();
  }

  @Test
  public void testErrorLogEmitsStructuredEnvelope() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    LogcoreHttpTransport.getInstance().setCustomSender(captured::set);

    Logger logger = LoggerFactory.getLogger(LogcoreIntegrationTest.class);
    logger.error("Test error message", new IllegalStateException("Test exception"));

    LogcoreHttpTransport.getInstance().flushSync();

    String json = captured.get();
    assertNotNull(json, "Payload should be captured");

    JsonNode root = objectMapper.readTree(json);
    assertEquals(1, root.get("schema_version").asInt());
    assertTrue(root.has("entries"));
    assertTrue(root.get("entries").isArray());
    assertEquals(1, root.get("entries").size());

    JsonNode entry = root.get("entries").get(0);
    assertEquals("ERROR", entry.get("severity").asText());
    assertEquals("Test error message", entry.get("message").asText());
    assertEquals("spring-boot-realworld-example-app", entry.get("service").asText());
    assertEquals("dev", entry.get("env").asText());
    assertTrue(entry.has("insert_id"));
    assertEquals(32, entry.get("insert_id").asText().length());

    assertTrue(entry.has("error"));
    JsonNode error = entry.get("error");
    assertEquals("java.lang.IllegalStateException", error.get("type").asText());
    assertEquals("Test exception", error.get("message").asText());
    assertTrue(error.has("stack"));
    assertTrue(error.get("stack").isArray());
    assertTrue(error.get("stack").size() > 0);

    JsonNode topFrame = error.get("stack").get(0);
    assertTrue(topFrame.has("function"));
    assertTrue(topFrame.has("inApp"));
    assertTrue(topFrame.get("inApp").asBoolean());
  }

  @Test
  public void testInfoLogNotEmitted() {
    AtomicReference<String> captured = new AtomicReference<>();
    LogcoreHttpTransport.getInstance().setCustomSender(captured::set);

    Logger logger = LoggerFactory.getLogger(LogcoreIntegrationTest.class);
    logger.info("Test info message - should be ignored");

    LogcoreHttpTransport.getInstance().flushSync();

    assertNull(captured.get(), "Info logs should not be forwarded");
  }

  @Test
  public void testTraceContextPropagation() throws Exception {
    AtomicReference<String> captured = new AtomicReference<>();
    LogcoreHttpTransport.getInstance().setCustomSender(captured::set);

    MDC.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    Logger logger = LoggerFactory.getLogger(LogcoreIntegrationTest.class);
    logger.error("Error with traceparent");

    LogcoreHttpTransport.getInstance().flushSync();

    String json = captured.get();
    assertNotNull(json);

    JsonNode root = objectMapper.readTree(json);
    JsonNode entry = root.get("entries").get(0);
    assertTrue(entry.has("trace"));
    JsonNode trace = entry.get("trace");
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.get("traceId").asText());
    assertEquals("00f067aa0ba902b7", trace.get("spanId").asText());
    assertTrue(trace.get("sampled").asBoolean());
  }
}
