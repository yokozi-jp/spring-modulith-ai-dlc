package com.example.demo.shared.infrastructure.persistence;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.annotation.Validated;

/** 未完了のイベント出版の定期の再投入の設定値と、有効なときだけの定期の実行を構成する（ADR-075）。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventPublicationResubmissionConfig.Properties.class)
class EventPublicationResubmissionConfig {

  /** 有効なときだけ @Scheduled と Staleness Monitor を動かす。 */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty("event-publication.resubmission.enabled")
  /* package */ static class Scheduling {}

  /**
   * 定期の再投入の設定値。
   *
   * @param enabled 定期の実行と Staleness Monitor を動かすか
   * @param waitAge 最後の試行から再投入するまでの待ち時間。リスナーの処理時間より十分に長くする
   * @param interval 前の回の終わりから次の回までの間隔
   * @param maxResubmissions 自動で再投入する回数の上限
   * @param maxPerRun 1 回の実行で再投入する件数と、新しく記録する ERROR の件数の上限
   */
  /* package */ @ConfigurationProperties("event-publication.resubmission")
  @Validated
  record Properties(
      boolean enabled,
      @NotNull @DurationMin(seconds = 1) Duration waitAge,
      @NotNull @DurationMin(seconds = 1) Duration interval,
      @Min(1) int maxResubmissions,
      @Min(1) int maxPerRun) {}
}
