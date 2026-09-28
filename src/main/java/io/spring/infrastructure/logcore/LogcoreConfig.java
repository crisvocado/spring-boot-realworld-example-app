package io.spring.infrastructure.logcore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class LogcoreConfig {

  private static final Pattern SERVICE_NAME_PATTERN =
      Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");
  private static final Set<String> ALLOWED_ENVS =
      new HashSet<>(Arrays.asList("prod", "staging", "dev", "test", "local"));

  private final String url;
  private final String apiKey;
  private final boolean enabled;
  private final String service;
  private final String env;
  private final String minSeverity;

  public LogcoreConfig() {
    this(
        getEnvOrProp(
            "LOGCORE_URL", "logcore.url", "https://logcore-dev-352942961463.us-east4.run.app"),
        getEnvOrProp("LOGCORE_KEY", "logcore.key", null),
        Boolean.parseBoolean(getEnvOrProp("LOGCORE_ENABLED", "logcore.enabled", "true")),
        normalizeService(
            getEnvOrProp(
                "LOGCORE_SERVICE", "logcore.service", "spring-boot-realworld-example-app")),
        normalizeEnv(getEnvOrProp("LOGCORE_ENV", "logcore.env", "dev")),
        getEnvOrProp("LOGCORE_MIN_SEVERITY", "logcore.min-severity", "ERROR"));
  }

  public LogcoreConfig(
      String url, String apiKey, boolean enabled, String service, String env, String minSeverity) {
    this.url =
        (url != null && !url.trim().isEmpty())
            ? url.trim()
            : "https://logcore-dev-352942961463.us-east4.run.app";
    this.apiKey = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : null;
    this.enabled = enabled;
    this.service = normalizeService(service);
    this.env = normalizeEnv(env);
    this.minSeverity =
        (minSeverity != null && !minSeverity.trim().isEmpty())
            ? minSeverity.trim().toUpperCase(Locale.ROOT)
            : "ERROR";
  }

  private static String getEnvOrProp(String envName, String propName, String defaultValue) {
    String envVal = System.getenv(envName);
    if (envVal != null && !envVal.trim().isEmpty()) {
      return envVal.trim();
    }
    String propVal = System.getProperty(propName);
    if (propVal != null && !propVal.trim().isEmpty()) {
      return propVal.trim();
    }
    return defaultValue;
  }

  public static String normalizeService(String name) {
    if (name == null || name.trim().isEmpty()) {
      return "spring-boot-realworld-example-app";
    }
    String normalized = name.trim().toLowerCase(Locale.ROOT);
    if (!SERVICE_NAME_PATTERN.matcher(normalized).matches()) {
      normalized = normalized.replaceAll("[^a-z0-9._-]", "-");
      if (normalized.length() > 63) {
        normalized = normalized.substring(0, 63);
      }
      if (!SERVICE_NAME_PATTERN.matcher(normalized).matches()) {
        normalized = "service-" + normalized;
        if (normalized.length() > 63) {
          normalized = normalized.substring(0, 63);
        }
      }
    }
    return normalized;
  }

  public static String normalizeEnv(String env) {
    if (env == null || env.trim().isEmpty()) {
      return "dev";
    }
    String normalized = env.trim().toLowerCase(Locale.ROOT);
    if ("production".equals(normalized)) {
      return "prod";
    }
    if (ALLOWED_ENVS.contains(normalized)) {
      return normalized;
    }
    return "dev";
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
}
