package com.example.demo;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Spring Boot が構成した OpenTelemetry を Logback appender へ設定する。 */
@Component
final class OpenTelemetryAppenderInitializer implements InitializingBean {

  /** Spring Boot が自動構成した OpenTelemetry インスタンス。 */
  private final OpenTelemetry openTelemetry;

  /** appender の初期化に使う OpenTelemetry インスタンスを受け取る。 */
  /* default */ OpenTelemetryAppenderInitializer(final OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
  }

  @Override
  public void afterPropertiesSet() {
    OpenTelemetryAppender.install(this.openTelemetry);
  }
}
