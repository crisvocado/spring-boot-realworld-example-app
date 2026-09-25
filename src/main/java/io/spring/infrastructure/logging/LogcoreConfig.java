package io.spring.infrastructure.logging;

import ch.qos.logback.classic.Level;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class LogcoreConfig {
  private static final String DEFAULT_URL = "https://logcore-dev-352942961463.us-east4.run.app";
  private static final String DEFAULT_SERVICE = "spring-boot-realworld-example-app";
  private static final String DEFAULT_ENV = "dev";
  private static final String DEFAULT_MIN_SEVERITY = "ERROR";
  private static final Set<String> VALID_ENVS =
      new HashSet<>(Arrays.asList("prod", "staging", "dev", "test", "local"));
  private static final Pattern SERVICE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");

  private final String url;
  private final String ingestUrl;
  private final String key;
  private final boolean enabled;
  private final String service;
  private final String env;
  private final String minSeverity;
  private final Level minLevel;
  private final int batchSize = 20;
  private final int maxBatchSize = 200;
  private final long flushIntervalMs = 5000;
  private final int queueCapacity = 1000;
  private final int maxRetries = 3;
  private final int circuitBreakerThreshold = 10;
  private final int maxMessageLength = 65536;
  private final int maxLabels = 32;
  private final int maxLabelValueLength = 1024;
  private final int maxStackFrames = 50;

  public LogcoreConfig() {
    this(
        System.getenv("LOGCORE_URL"),
        System.getenv("LOGCORE_KEY"),
        System.getenv("LOGCORE_ENABLED"),
        System.getenv("LOGCORE_SERVICE"),
        System.getenv("LOGCORE_ENV"),
        System.getenv("LOGCORE_MIN_SEVERITY"));
  }

  public LogcoreConfig(
      String rawUrl,
      String rawKey,
      String rawEnabled,
      String rawService,
      String rawEnv,
      String rawMinSeverity) {
    String resolvedUrl = (rawUrl != null && !rawUrl.trim().isEmpty()) ? rawUrl.trim() : DEFAULT_URL;
    if (resolvedUrl.endsWith("/")) {
      resolvedUrl = resolvedUrl.substring(0, resolvedUrl.length() - 1);
    }
    this.url = resolvedUrl;
    this.ingestUrl = resolvedUrl.endsWith("/v1/logs") ? resolvedUrl : resolvedUrl + "/v1/logs";

    this.key = (rawKey != null) ? rawKey.trim() : "";

    if (rawEnabled != null) {
      String lower = rawEnabled.trim().toLowerCase(Locale.ROOT);
      this.enabled =
          !(lower.equals("false")
              || lower.equals("0")
              || lower.equals("no")
              || lower.equals("off"));
    } else {
      this.enabled = true;
    }

    String resolvedService = DEFAULT_SERVICE;
    if (rawService != null && !rawService.trim().isEmpty()) {
      String candidate = rawService.trim().toLowerCase(Locale.ROOT);
      if (SERVICE_PATTERN.matcher(candidate).matches()) {
        resolvedService = candidate;
      }
    }
    this.service = resolvedService;

    String resolvedEnv = DEFAULT_ENV;
    if (rawEnv != null && !rawEnv.trim().isEmpty()) {
      String candidate = rawEnv.trim().toLowerCase(Locale.ROOT);
      if ("production".equals(candidate)) {
        candidate = "prod";
      } else if ("development".equals(candidate)) {
        candidate = "dev";
      }
      if (VALID_ENVS.contains(candidate)) {
        resolvedEnv = candidate;
      }
    }
    this.env = resolvedEnv;

    String resolvedMinSeverity = DEFAULT_MIN_SEVERITY;
    if (rawMinSeverity != null && !rawMinSeverity.trim().isEmpty()) {
      resolvedMinSeverity = rawMinSeverity.trim().toUpperCase(Locale.ROOT);
    }
    this.minSeverity = resolvedMinSeverity;
    this.minLevel = parseLevel(this.minSeverity);
  }

  private static Level parseLevel(String levelStr) {
    if ("WARN".equalsIgnoreCase(levelStr) || "WARNING".equalsIgnoreCase(levelStr)) {
      return Level.WARN;
    }
    if ("INFO".equalsIgnoreCase(levelStr)) {
      return Level.INFO;
    }
    if ("DEBUG".equalsIgnoreCase(levelStr)) {
      return Level.DEBUG;
    }
    if ("TRACE".equalsIgnoreCase(levelStr)) {
      return Level.TRACE;
    }
    return Level.ERROR;
  }

  public String getUrl() {
    return url;
  }

  public String getIngestUrl() {
    return ingestUrl;
  }

  public String getKey() {
    return key;
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

  public Level getMinLevel() {
    return minLevel;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public int getMaxBatchSize() {
    return maxBatchSize;
  }

  public long getFlushIntervalMs() {
    return flushIntervalMs;
  }

  public int getQueueCapacity() {
    return queueCapacity;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public int getCircuitBreakerThreshold() {
    return circuitBreakerThreshold;
  }

  public int getMaxMessageLength() {
    return maxMessageLength;
  }

  public int getMaxLabels() {
    return maxLabels;
  }

  public int getMaxLabelValueLength() {
    return maxLabelValueLength;
  }

  public int getMaxStackFrames() {
    return maxStackFrames;
  }
}
