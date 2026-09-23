package io.spring.logcore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LogcoreConfig {
  public static final String DEFAULT_URL =
      "https://logcore-dev-352942961463.us-east4.run.app";
  public static final String DEFAULT_SERVICE = "spring-boot-realworld-example-app";
  public static final String DEFAULT_ENV = "dev";
  public static final String DEFAULT_MIN_SEVERITY = "ERROR";

  private static final Set<String> VALID_ENVS =
      new HashSet<>(Arrays.asList("prod", "staging", "dev", "test", "local"));

  private final String url;
  private final String apiKey;
  private final boolean enabled;
  private final String service;
  private final String env;
  private final String minSeverity;

  public LogcoreConfig() {
    this(
        getEnvOrProp("LOGCORE_URL", DEFAULT_URL),
        getEnvOrProp("LOGCORE_KEY", ""),
        getBooleanEnvOrProp("LOGCORE_ENABLED", true),
        getEnvOrProp("LOGCORE_SERVICE", DEFAULT_SERVICE),
        normalizeEnv(getEnvOrProp("LOGCORE_ENV", DEFAULT_ENV)),
        getEnvOrProp("LOGCORE_MIN_SEVERITY", DEFAULT_MIN_SEVERITY));
  }

  public LogcoreConfig(
      String url,
      String apiKey,
      boolean enabled,
      String service,
      String env,
      String minSeverity) {
    this.url = sanitizeUrl(url);
    this.apiKey = apiKey != null ? apiKey.trim() : "";
    this.enabled = enabled;
    this.service = sanitizeService(service);
    this.env = normalizeEnv(env);
    this.minSeverity = minSeverity != null ? minSeverity.trim().toUpperCase() : "ERROR";
  }

  public String getUrl() {
    return url;
  }

  public String getApiKey() {
    return apiKey;
  }

  public boolean isEnabled() {
    return enabled;
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

  private static String getEnvOrProp(String name, String defaultValue) {
    String val = System.getenv(name);
    if (val != null && !val.trim().isEmpty()) {
      return val.trim();
    }
    val = System.getProperty(name);
    if (val != null && !val.trim().isEmpty()) {
      return val.trim();
    }
    val = System.getProperty(name.toLowerCase().replace('_', '.'));
    if (val != null && !val.trim().isEmpty()) {
      return val.trim();
    }
    return defaultValue;
  }

  private static boolean getBooleanEnvOrProp(String name, boolean defaultValue) {
    String val = getEnvOrProp(name, null);
    if (val == null) {
      return defaultValue;
    }
    return !"false".equalsIgnoreCase(val) && !"0".equals(val);
  }

  public static String normalizeEnv(String env) {
    if (env == null || env.trim().isEmpty()) {
      return DEFAULT_ENV;
    }
    String normalized = env.trim().toLowerCase();
    if ("production".equals(normalized)) {
      return "prod";
    }
    if ("development".equals(normalized)) {
      return "dev";
    }
    if (VALID_ENVS.contains(normalized)) {
      return normalized;
    }
    return DEFAULT_ENV;
  }

  public static String sanitizeService(String service) {
    if (service == null || service.trim().isEmpty()) {
      return DEFAULT_SERVICE;
    }
    String sanitized = service.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "-");
    if (sanitized.isEmpty() || !Character.isLetterOrDigit(sanitized.charAt(0))) {
      sanitized = "app-" + sanitized;
    }
    if (sanitized.length() > 63) {
      sanitized = sanitized.substring(0, 63);
    }
    return sanitized;
  }

  private static String sanitizeUrl(String url) {
    if (url == null || url.trim().isEmpty()) {
      return DEFAULT_URL;
    }
    String clean = url.trim();
    while (clean.endsWith("/")) {
      clean = clean.substring(0, clean.length() - 1);
    }
    if (clean.endsWith("/v1/logs")) {
      clean = clean.substring(0, clean.length() - "/v1/logs".length());
    }
    return clean;
  }
}
