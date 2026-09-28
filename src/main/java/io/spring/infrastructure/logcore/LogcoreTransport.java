package io.spring.infrastructure.logcore;

public interface LogcoreTransport {
  void send(LogcoreEnvelope envelope, String ingestUrl, String apiKey) throws Exception;
}
