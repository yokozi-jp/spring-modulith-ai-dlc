package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

/** 構造化ログ、トレース相関、OTLP 属性の運用契約を検証する。 */
@Slf4j
@SpringBootTest
@Import(SharedTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityContractTest {

  /** HTTP などと同じ tracing handler が登録された observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

  /** OTLP へ送る直前の LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** このコンテキストの OpenTelemetry。 */
  @Autowired private OpenTelemetry openTelemetry;

  @BeforeEach
  void installAppender() {
    // 別のコンテキストが appender の送り先を差し替えていても、このコンテキストの記録を読む。
    OpenTelemetryAppender.install(openTelemetry);
  }

  @Test
  @DisplayName("span 内の JSON ログには trace ID と span ID が含まれる")
  void structuredLogContainsTraceAndSpanIdentifiers(final CapturedOutput output) {
    final AtomicReference<String> traceId = new AtomicReference<>();
    final AtomicReference<String> spanId = new AtomicReference<>();
    final String message = "observability correlation contract";

    Observation.createNotStarted("observability.contract", observationRegistry)
        .observe(
            () -> {
              traceId.set(MDC.get("traceId"));
              spanId.set(MDC.get("spanId"));
              log.info(message);
            });

    assertNotNull(traceId.get(), "active observation の trace ID が MDC に存在すること");
    assertNotNull(spanId.get(), "active observation の span ID が MDC に存在すること");
    assertTrue(
        output.getAll().lines().anyMatch(line -> line.startsWith("{") && line.contains(message)),
        "対象ログが一行 JSON で出力されること");
    assertTrue(output.getAll().contains(traceId.get()), "JSON ログに trace ID が含まれること");
    assertTrue(output.getAll().contains(spanId.get()), "JSON ログに span ID が含まれること");
  }

  @Test
  @DisplayName("SLF4J の key-value は OTLP の LogRecord 属性になる")
  // appender が key-value を属性にすることだけを確かめる。order.id は Collector の allowlist にないため、
  // 実際の出口では除かれる（docs/observability/conventions.md）。
  void keyValuePairsBecomeLogRecordAttributes() {
    final String event = "observability key-value contract";

    log.atInfo().addKeyValue("order.id", "order-1").log(event);

    final Attributes attributes = singleRecordAttributes(event);
    assertEquals(
        "order-1",
        attributes.get(AttributeKey.stringKey("order.id")),
        () -> event + ": " + attributes);
  }

  @Test
  @DisplayName("logger へ渡した例外は OTLP の LogRecord に exception 属性として載る")
  void exceptionBecomesLogRecordAttributes() {
    final String event = "observability exception contract";

    log.atError().setCause(new IllegalStateException("contract failure")).log(event);

    final Attributes attributes = singleRecordAttributes(event);
    assertEquals(
        IllegalStateException.class.getName(),
        attributes.get(AttributeKey.stringKey("exception.type")),
        () -> event + ": " + attributes);
    assertEquals(
        "contract failure",
        attributes.get(AttributeKey.stringKey("exception.message")),
        () -> event + ": " + attributes);
    final String stackTrace = attributes.get(AttributeKey.stringKey("exception.stacktrace"));
    assertTrue(
        stackTrace != null && stackTrace.contains("ObservabilityContractTest"),
        () -> event + ": " + attributes);
  }

  private Attributes singleRecordAttributes(final String event) {
    final List<LogRecordData> records = capturedLogRecords.withBody(event);
    assertEquals(1, records.size(), () -> "body=" + event + " の LogRecord 件数");
    return records.getFirst().getAttributes();
  }
}
