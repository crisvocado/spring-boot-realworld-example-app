package io.spring.infrastructure.logcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class HttpLogcoreTransport implements LogcoreTransport {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public HttpLogcoreTransport() {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = new ObjectMapper();
  }

  public HttpLogcoreTransport(HttpClient httpClient, ObjectMapper objectMapper) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public void send(LogcoreEnvelope envelope, String ingestUrl, String apiKey) throws Exception {
    if (envelope == null || envelope.getEntries() == null || envelope.getEntries().isEmpty()) {
      return;
    }
    if (ingestUrl == null || ingestUrl.trim().isEmpty()) {
      return;
    }

    String jsonBody = objectMapper.writeValueAsString(envelope);
    byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

    int maxRetries = 3;
    long baseDelayMs = 250;

    for (int attempt = 0; attempt < maxRetries; attempt++) {
      try {
        HttpRequest.Builder builder =
            HttpRequest.newBuilder()
                .uri(URI.create(ingestUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));

        if (apiKey != null && !apiKey.trim().isEmpty()) {
          builder.header("x-api-key", apiKey.trim());
        }

        HttpRequest request = builder.build();
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int statusCode = response.statusCode();

        if (statusCode >= 200 && statusCode < 300) {
          // Success
          return;
        }

        if (statusCode == 429 || statusCode >= 500) {
          // Retryable error
          if (attempt < maxRetries - 1) {
            long sleepTime = (long) (Math.pow(2, attempt) * baseDelayMs);
            try {
              Thread.sleep(sleepTime);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return;
            }
            continue;
          } else {
            throw new RuntimeException(
                "Logcore delivery failed with status "
                    + statusCode
                    + " after "
                    + maxRetries
                    + " attempts: "
                    + response.body());
          }
        } else {
          // Permanent 4xx rejection - do not retry, echo server's reason to stderr
          System.err.println(
              "Logcore rejected batch with HTTP " + statusCode + ": " + response.body());
          return;
        }
      } catch (java.io.IOException e) {
        if (attempt < maxRetries - 1) {
          long sleepTime = (long) (Math.pow(2, attempt) * baseDelayMs);
          try {
            Thread.sleep(sleepTime);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
          }
        } else {
          throw e;
        }
      }
    }
  }
}
