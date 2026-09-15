package com.example.demo.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** OpenTelemetry appender が許可した構造化属性だけを転送することを検証する。 */
class ObservabilityConfigurationTest {

  /** 検証対象の Logback 設定。 */
  private static final Path LOGBACK_CONFIG =
      Path.of("src", "main", "resources", "logback-spring.xml");

  @Test
  @DisplayName("OTLP へ転送する key-value 属性を例外診断属性へ限定する")
  void otelAppenderAllowsOnlyExceptionDiagnosticAttributes() {
    final String config = readLogbackConfig();

    assertTrue(
        config.contains(
            "<keyValuePairAttributesIncluded>exception.type,exception.stacktrace"
                + "</keyValuePairAttributesIncluded>"),
        "OTel appender の key-value allowlist");
    assertFalse(
        config.contains("<captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>"),
        "非推奨の全 key-value capture を有効にしないこと");
  }

  private static String readLogbackConfig() {
    try {
      return Files.readString(LOGBACK_CONFIG);
    } catch (final IOException exception) {
      throw new UncheckedIOException("Logback 設定を読み取れません: " + LOGBACK_CONFIG, exception);
    }
  }
}
