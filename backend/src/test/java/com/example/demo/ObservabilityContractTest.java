package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

/** 構造化ログとトレース相関の運用契約を検証する。 */
@Slf4j
@SpringBootTest
@Import(SharedTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityContractTest {

  /** HTTP などと同じ tracing handler が登録された observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

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
}
