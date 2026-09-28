package io.spring.infrastructure.logcore;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class LogcoreIntegrationTest {

  private LoggerContext loggerContext;
  private Logger logger;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    logger = loggerContext.getLogger("io.spring.test.LogcoreIntegrationTest");
    objectMapper = new ObjectMapper();
    MDC.clear();
    TraceContextHolder.clear();
  }

  @AfterEach
  public void tearDown() {
    MDC.clear();
    TraceContextHolder.clear();
  }

  @Test
  public void testLogcoreEntryFormatMatchesGoldenEntry() throws Exception {
    List<LogcoreEnvelope> capturedEnvelopes = Collections.synchronizedList(new ArrayList<>());
    LogcoreTransport mockTransport =
        (envelope, ingestUrl, apiKey) -> capturedEnvelopes.add(envelope);

    LogcoreConfig config =
        new LogcoreConfig(
            "https://logcore-dev-352942961463.us-east4.run.app",
            "test-key",
            true,
            "spring-boot-realworld-example-app",
            "dev",
            "ERROR");

    LogcoreClient client = new LogcoreClient(config, mockTransport);
    LogcoreAppender appender = new LogcoreAppender(client);
    appender.setContext(loggerContext);
    appender.start();

    TraceContext traceContext =
        new TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", true);
    TraceContextHolder.set(traceContext);

    Exception rootCause = new IllegalArgumentException("Root cause error");
    Exception wrapped = new RuntimeException("Outer failure", rootCause);

    LoggingEvent event =
        new LoggingEvent(
            "io.spring.TestService",
            logger,
            Level.ERROR,
            "Processing checkout failed",
            wrapped,
            null);

    LogcoreEntry entry = appender.buildEntry(event);

    assertNotNull(entry.getTimestamp());
    assertEquals("ERROR", entry.getSeverity());
    assertEquals("Processing checkout failed", entry.getMessage());
    assertEquals("spring-boot-realworld-example-app", entry.getService());
    assertEquals("dev", entry.getEnv());
    assertNotNull(entry.getInsertId());
    assertEquals(32, entry.getInsertId().length());
    assertTrue(entry.getInsertId().matches("^[0-9a-f]{32}$"));

    assertNotNull(entry.getTrace());
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", entry.getTrace().getTraceId());
    assertEquals("00f067aa0ba902b7", entry.getTrace().getSpanId());
    assertTrue(entry.getTrace().getSampled());

    assertNotNull(entry.getError());
    assertEquals("RuntimeException", entry.getError().getType());
    assertEquals("Outer failure", entry.getError().getMessage());
    assertNotNull(entry.getError().getStack());
    assertFalse(entry.getError().getStack().isEmpty());

    LogcoreStackFrame firstFrame = entry.getError().getStack().get(0);
    assertNotNull(firstFrame.getFunction());
    assertNotNull(firstFrame.getFile());
    assertTrue(firstFrame.getLine() >= 0);
    assertEquals(0, firstFrame.getColumn());

    LogcoreEnvelope envelope = new LogcoreEnvelope(Collections.singletonList(entry));
    assertEquals(1, envelope.getSchemaVersion());

    String json = objectMapper.writeValueAsString(envelope);
    JsonNode node = objectMapper.readTree(json);

    assertEquals(1, node.get("schema_version").asInt());
    JsonNode entriesNode = node.get("entries");
    assertTrue(entriesNode.isArray());
    assertEquals(1, entriesNode.size());

    JsonNode entryNode = entriesNode.get(0);
    assertTrue(entryNode.has("timestamp"));
    assertTrue(entryNode.has("severity"));
    assertTrue(entryNode.has("message"));
    assertTrue(entryNode.has("service"));
    assertTrue(entryNode.has("env"));
    assertTrue(entryNode.has("insert_id"));
    assertTrue(entryNode.has("trace"));
    assertTrue(entryNode.has("error"));

    JsonNode traceNode = entryNode.get("trace");
    assertTrue(traceNode.has("traceId"));
    assertTrue(traceNode.has("spanId"));
    assertTrue(traceNode.has("sampled"));

    JsonNode errorNode = entryNode.get("error");
    assertTrue(errorNode.has("type"));
    assertTrue(errorNode.has("message"));
    assertTrue(errorNode.has("stack"));

    JsonNode stackNode = errorNode.get("stack");
    assertTrue(stackNode.isArray());
    JsonNode frameNode = stackNode.get(0);
    assertTrue(frameNode.has("function"));
    assertTrue(frameNode.has("file"));
    assertTrue(frameNode.has("line"));
    assertTrue(frameNode.has("column"));
    assertTrue(frameNode.has("inApp"));

    client.close();
  }

  @Test
  public void testTraceContextParsing() {
    String header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    TraceContext context = TraceContext.fromHeader(header);
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.getTraceId());
    assertEquals("00f067aa0ba902b7", context.getSpanId());
    assertTrue(context.isSampled());
    assertEquals(header, context.toTraceparentHeader());

    TraceContext generated = TraceContext.generate();
    assertEquals(32, generated.getTraceId().length());
    assertEquals(16, generated.getSpanId().length());
    assertTrue(generated.isSampled());
  }

  @Test
  public void testCircuitBreakerAndRetries() throws Exception {
    AtomicInteger attempts = new AtomicInteger(0);
    LogcoreTransport failingTransport =
        (envelope, ingestUrl, apiKey) -> {
          attempts.incrementAndGet();
          throw new java.io.IOException("Network unavailable");
        };

    LogcoreConfig config =
        new LogcoreConfig(
            "https://logcore-dev-352942961463.us-east4.run.app",
            "test-key",
            true,
            "test-service",
            "dev",
            "ERROR");

    LogcoreClient client = new LogcoreClient(config, failingTransport);

    for (int i = 0; i < 15; i++) {
      LogcoreEntry entry = new LogcoreEntry();
      entry.setTimestamp("2026-08-17T18:30:00.000Z");
      entry.setSeverity("ERROR");
      entry.setMessage("Test error " + i);
      entry.setService("test-service");
      entry.setEnv("dev");
      entry.setInsertId(String.format("%032d", i));
      client.enqueue(entry);
      client.flush();
    }

    assertTrue(client.getConsecutiveFailures() >= 10);
    assertEquals(0, client.getBufferSize());

    client.close();
  }
}
