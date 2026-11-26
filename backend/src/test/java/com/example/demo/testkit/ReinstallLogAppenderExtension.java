package com.example.demo.testkit;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 各テストの前に、Logback の OpenTelemetry appender の送り先を、このテストのコンテキストの {@link OpenTelemetry} に戻す JUnit 拡張。
 *
 * <p>appender は全コンテキストで 1 つで、別のコンテキストが送り先を差し替えていると、{@link CapturedLogRecords} がこのコンテキストの記録を読めない。
 */
public final class ReinstallLogAppenderExtension implements BeforeEachCallback {

  @Override
  public void beforeEach(final ExtensionContext context) {
    OpenTelemetryAppender.install(
        SpringExtension.getApplicationContext(context).getBean(OpenTelemetry.class));
  }
}
