package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.registry.otlp.OtlpConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * OTLP の指標の resource が、ログとトレースと同じ {@code service.instance.id} を持つことを検証する（ADR-133）。
 *
 * <p>テストのコンテキストは、既定では OTLP の指標のレジストリを作らない。この class だけが有効にして、Spring Boot が指標の送信に使う {@link
 * OtlpConfig} を取り出す。送信先は閉じたポートで、送信の間隔は 1 時間にして、テストの間は送信しない。
 */
@SpringBootTest(
    properties = {
      "management.otlp.metrics.export.enabled=true",
      "management.otlp.metrics.export.step=1h",
      "management.otlp.metrics.export.url=http://127.0.0.1:1/v1/metrics"
    })
@Import(SharedTestConfiguration.class)
class MetricsResourceContractTest {

  /** 起動ごとに変わるインスタンスの識別子の項目。 */
  private static final String SERVICE_INSTANCE_ID = "service.instance.id";

  /** 指標の送信の設定。resource の属性を持つ。 */
  @Autowired private OtlpConfig otlpConfig;

  /** ログとトレースが使う resource。 */
  @Autowired private Resource resource;

  @Test
  @DisplayName("指標の resource は、ログとトレースの resource と同じ service.instance.id を持つ")
  void metricsResourceSharesServiceInstanceIdWithLogsAndTraces() {
    final String fromMetrics = otlpConfig.resourceAttributes().get(SERVICE_INSTANCE_ID);
    assertNotNull(fromMetrics, "指標の resource の " + SERVICE_INSTANCE_ID);
    assertEquals(
        resource.getAttribute(AttributeKey.stringKey(SERVICE_INSTANCE_ID)),
        fromMetrics,
        "ログとトレースの resource の " + SERVICE_INSTANCE_ID);
  }
}
