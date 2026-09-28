package io.spring.infrastructure.logcore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LogcoreConfig {

  public static final String ENV_LOGCORE_URL = "LOGCORE_URL";
  public static final String ENV_LOGCORE_KEY = "LOGCORE_KEY";
  public static final String ENV_LOGCORE_ENABLED = "LOGCORE_ENABLED";
  public static final String ENV_LOGCORE_SERVICE = "LOGCORE_SERVICE";
  public static final String ENV_LOGCORE_ENV = "LOGCORE_ENV";
  public static final String ENV_LOGCORE_MIN_SEVERITY = "LOGCORE_MIN_SEVERITY";

  public static final String DEFAULT_ENDPOINT = "https://logcore-dev-352942961463.us-east4.run.app";
  public static final String DEFAULT_SERVICE = "spring-boot-realworld-example-app";
  public static final String DEFAULT_ENV = "dev";
  public static final String DEFAULT_MIN_SEVERITY = "ERROR";

  private static final Set<String> VALID_ENVS =
      new HashSet<>(Arrays.asList("prod", "staging", "dev", "test", "local"));

  private String url;
  private String key;
  private boolean enabled;
  private String service;
  private String env;
  private String minSeverity;

  public LogcoreConfig() {
    this.url = getEnvOrProperty(ENV_LOGCORE_URL, DEFAULT_ENDPOINT);
    this.key = getEnvOrProperty(ENV_LOGCORE_KEY, null);
    String enabledStr = getEnvOrProperty(ENV_LOGCORE_ENABLED, null);
    if (enabledStr != null) {
      this.enabled = "true".equalsIgnoreCase(enabledStr.trim()) || "1".equals(enabledStr.trim());
    } else {
      this.enabled = this.key != null && !this.key.trim().isEmpty();
    }
    this.service = getEnvOrProperty(ENV_LOGCORE_SERVICE, DEFAULT_SERVICE);
    String detectedEnv = getEnvOrProperty(ENV_LOGCORE_ENV, DEFAULT_ENV);
    this.env = VALID_ENVS.contains(detectedEnv) ? detectedEnv : DEFAULT_ENV;
    this.minSeverity = getEnvOrProperty(ENV_LOGCORE_MIN_SEVERITY, DEFAULT_MIN_SEVERITY);
  }

  public LogcoreConfig(
      String url, String key, boolean enabled, String service, String env, String minSeverity) {
    this.url = url != null ? url : DEFAULT_ENDPOINT;
    this.key = key;
    this.enabled = enabled;
    this.service = service != null ? service : DEFAULT_SERVICE;
    this.env = (env != null && VALID_ENVS.contains(env)) ? env : DEFAULT_ENV;
    this.minSeverity = minSeverity != null ? minSeverity : DEFAULT_MIN_SEVERITY;
  }

  private static String getEnvOrProperty(String name, String defaultValue) {
    String val = System.getenv(name);
    if (val != null && !val.trim().isEmpty()) {
      return val.trim();
    }
    val = System.getProperty(name);
    if (val != null && !val.trim().isEmpty()) {
      return val.trim();
    }
    return defaultValue;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getService() {
    return service;
  }

  public void setService(String service) {
    this.service = service;
  }

  public String getEnv() {
    return env;
  }

  public void setEnv(String env) {
    this.env = env;
  }

  public String getMinSeverity() {
    return minSeverity;
  }

  public void setMinSeverity(String minSeverity) {
    this.minSeverity = minSeverity;
  }

  public String getIngestUrl() {
    if (url == null || url.trim().isEmpty()) {
      return DEFAULT_ENDPOINT + "/v1/logs";
    }
    String trimmed = url.trim();
    if (trimmed.endsWith("/v1/logs")) {
      return trimmed;
    }
    if (trimmed.endsWith("/")) {
      return trimmed + "v1/logs";
    }
    return trimmed + "/v1/logs";
  }
}
