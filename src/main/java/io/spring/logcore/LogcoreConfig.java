package io.spring.logcore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class LogcoreConfig {

  private static final Pattern SERVICE_PATTERN =
      Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");
  private static final Set<String> ALLOWED_ENVS =
      new HashSet<>(Arrays.asList("prod", "staging", "dev", "test", "local"));

  private final String url;
  private final String apiKey;
  private final boolean enabled;
  private final String service;
  private final String env;
  private final String minSeverity;

  private static volatile LogcoreConfig instance;

  public static LogcoreConfig getInstance() {
    if (instance == null) {
      synchronized (LogcoreConfig.class) {
        if (instance == null) {
          instance = fromEnv();
        }
      }
    }
    return instance;
  }

  public static void resetInstance() {
    synchronized (LogcoreConfig.class) {
      instance = null;
    }
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
    this.env = sanitizeEnv(env);
    this.minSeverity =
        minSeverity != null && !minSeverity.trim().isEmpty()
            ? minSeverity.trim().toUpperCase(Locale.ROOT)
            : "ERROR";
  }

  public static LogcoreConfig fromEnv() {
    String url = System.getenv("LOGCORE_URL");
    if (url == null || url.trim().isEmpty()) {
      url = "https://logcore-dev-352942961463.us-east4.run.app";
    }

    String apiKey = System.getenv("LOGCORE_KEY");

    String enabledStr = System.getenv("LOGCORE_ENABLED");
    boolean enabled = true;
    if (enabledStr != null && enabledStr.trim().equalsIgnoreCase("false")) {
      enabled = false;
    }

    String service = System.getenv("LOGCORE_SERVICE");
    if (service == null || service.trim().isEmpty()) {
      service = "spring-boot-realworld-example-app";
    }

    String env = System.getenv("LOGCORE_ENV");
    if (env == null || env.trim().isEmpty()) {
      env = "dev";
    }

    String minSeverity = System.getenv("LOGCORE_MIN_SEVERITY");
    if (minSeverity == null || minSeverity.trim().isEmpty()) {
      minSeverity = "ERROR";
    }

    return new LogcoreConfig(url, apiKey, enabled, service, env, minSeverity);
  }

  private static String sanitizeUrl(String rawUrl) {
    if (rawUrl == null || rawUrl.trim().isEmpty()) {
      return "https://logcore-dev-352942961463.us-east4.run.app";
    }
    String trimmed = rawUrl.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static String sanitizeService(String rawService) {
    if (rawService == null || rawService.trim().isEmpty()) {
      return "spring-boot-realworld-example-app";
    }
    String s = rawService.trim().toLowerCase(Locale.ROOT);
    s = s.replaceAll("[^a-z0-9._-]", "-");
    while (s.length() > 0 && !Character.isLetterOrDigit(s.charAt(0))) {
      s = s.substring(1);
    }
    if (s.isEmpty()) {
      s = "spring-boot-realworld-example-app";
    }
    if (s.length() > 63) {
      s = s.substring(0, 63);
    }
    if (!SERVICE_PATTERN.matcher(s).matches()) {
      return "spring-boot-realworld-example-app";
    }
    return s;
  }

  private static String sanitizeEnv(String rawEnv) {
    if (rawEnv == null || rawEnv.trim().isEmpty()) {
      return "dev";
    }
    String e = rawEnv.trim().toLowerCase(Locale.ROOT);
    if ("production".equals(e)) {
      return "prod";
    }
    if ("development".equals(e)) {
      return "dev";
    }
    if (ALLOWED_ENVS.contains(e)) {
      return e;
    }
    return "dev";
  }

  public String getUrl() {
    return url;
  }

  public String getEndpoint() {
    if (url.endsWith("/v1/logs")) {
      return url;
    }
    return url + "/v1/logs";
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
