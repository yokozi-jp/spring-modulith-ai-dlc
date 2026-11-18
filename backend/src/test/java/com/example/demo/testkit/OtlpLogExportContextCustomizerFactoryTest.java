package com.example.demo.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** テストのコンテキストが OTLP へ送る exporter を持たないことを確かめる。 */
@SpringBootTest
@Import(SharedTestConfiguration.class)
class OtlpLogExportContextCustomizerFactoryTest {

  /** exporter の有無を調べるコンテキスト。 */
  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("テストのコンテキストは、OTLP の送信先へ接続するログ、トレース、メトリクスの exporter を持たない")
  void contextHasNoOtlpExporter() {
    final List<String> exporters =
        Arrays.stream(context.getBeanDefinitionNames())
            .filter(name -> isOtlpExporter(context.getType(name)))
            .toList();

    assertEquals(List.of(), exporters, "OTLP へ送る exporter の Bean");
  }

  // exporter の型はコンパイルの classpath にないので、パッケージの名前で見分ける。
  private static boolean isOtlpExporter(final Class<?> type) {
    return type != null
        && (type.getName().startsWith("io.opentelemetry.exporter.otlp.")
            || type.getName().startsWith("io.micrometer.registry.otlp."));
  }
}
