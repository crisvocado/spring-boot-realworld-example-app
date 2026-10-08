package io.spring.logcore;

import java.util.Locale;

public class LogcoreConfig {

  private static final String DEFAULT_URL =
      "https://logcore-dev-352942961463.us-east4.run.app";
  private static final String DEFAULT_SERVICE = "spring-boot-realworld-example-app";
  private static final String DEFAULT_ENV = "dev";
  private static final String DEFAULT_MIN_SEVERITY = "ERROR";

  private final boolean enabled;
  private final String url;
  private final String apiKey;
  private final String service;
  private final String env;
  private final String minSeverity;

  public LogcoreConfig() {
    String enabledEnv = getEnvOrProp("LOGCORE_ENABLED");
    String apiKeyEnv = getEnvOrProp("LOGCORE_KEY");
    String urlEnv = getEnvOrProp("LOGCORE_URL");
    String serviceEnv = getEnvOrProp("LOGCORE_SERVICE");
    String envEnv = getEnvOrProp("LOGCORE_ENV");
    String minSeverityEnv = getEnvOrProp("LOGCORE_MIN_SEVERITY");

    this.url = (urlEnv != null && !urlEnv.isBlank()) ? normalizeUrl(urlEnv) : normalizeUrl(DEFAULT_URL);
    this.apiKey = (apiKeyEnv != null) ? apiKeyEnv.trim() : "";
    this.service = (serviceEnv != null && !serviceEnv.isBlank()) ? sanitizeService(serviceEnv) : DEFAULT_SERVICE;
    this.env = (envEnv != null && !envEnv.isBlank()) ? normalizeEnv(envEnv) : DEFAULT_ENV;
    this.minSeverity = (minSeverityEnv != null && !minSeverityEnv.isBlank()) ? minSeverityEnv.toUpperCase(Locale.ROOT) : DEFAULT_MIN_SEVERITY;

    if (enabledEnv != null) {
      this.enabled = "true".equalsIgnoreCase(enabledEnv.trim()) || "1".equals(enabledEnv.trim());
    } else {
      this.enabled = !this.apiKey.isEmpty();
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getUrl() {
    return url;
  }

  public String getApiKey() {
    return apiKey;
  }

  public String getService() {
    return service;
  }

  public String getEnv() {
    return env;
  }

  public String getMinSeverity() {
    return minSeverity;
  }

  private static String getEnvOrProp(String name) {
    String val = System.getenv(name);
    if (val != null) {
      return val;
    }
    return System.getProperty(name);
  }

  private static String normalizeUrl(String rawUrl) {
    String trimmed = rawUrl.trim();
    if (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    if (!trimmed.endsWith("/v1/logs")) {
      trimmed = trimmed + "/v1/logs";
    }
    return trimmed;
  }

  private static String sanitizeService(String rawService) {
    String sanitized = rawService.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    if (sanitized.isEmpty()) {
      return DEFAULT_SERVICE;
    }
    if (sanitized.length() > 63) {
      sanitized = sanitized.substring(0, 63);
    }
    return sanitized;
  }

  private static String normalizeEnv(String rawEnv) {
    String lower = rawEnv.toLowerCase(Locale.ROOT).trim();
    switch (lower) {
      case "production":
      case "prod":
        return "prod";
      case "staging":
      case "stage":
        return "staging";
      case "development":
      case "dev":
        return "dev";
      case "testing":
      case "test":
        return "test";
      case "local":
        return "local";
      default:
        return "dev";
    }
  }
}
