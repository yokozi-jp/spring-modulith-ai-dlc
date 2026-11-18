package com.example.demo.testkit;

import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Spring のテストのコンテキストで、OTLP のログ exporter を作らない。
 *
 * <p>{@code .env.test} の OTLP の送信先に Collector はない。exporter が残ると、キャッシュしたコンテキストの背景のスレッドが、 送信の失敗を
 * ERROR で標準出力へ出し、{@code OutputCaptureExtension} で出力を調べるテストが不安定になる。 トレースとメトリクスの exporter は、Spring
 * Boot の {@code TracingContextCustomizerFactory} と {@code MetricsContextCustomizerFactory}
 * が同じ方法で止める。 ログにはその factory がないので、ここで補う。
 */
public final class OtlpLogExportContextCustomizerFactory implements ContextCustomizerFactory {

  @Override
  public ContextCustomizer createContextCustomizer(
      final Class<?> testClass, final List<ContextConfigurationAttributes> configAttributes) {
    return new Customizer();
  }

  /** すべてのテストで等しく、コンテキストのキャッシュの鍵を分けない。 */
  private record Customizer() implements ContextCustomizer {

    @Override
    public void customizeContext(
        final ConfigurableApplicationContext context,
        final MergedContextConfiguration mergedConfig) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "testOtlpLogExport", Map.of("management.logging.export.otlp.enabled", "false")));
    }
  }
}
