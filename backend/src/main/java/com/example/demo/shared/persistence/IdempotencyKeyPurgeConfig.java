package com.example.demo.shared.persistence;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;

/**
 * 期限を過ぎた {@code Idempotency-Key} の行の掃除（{@link
 * IdempotencyKeyPurge}）の設定値と、有効なときだけの定期の実行を構成する（ADR-126）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyKeyPurgeConfig.Properties.class)
class IdempotencyKeyPurgeConfig {

  /**
   * 有効なときだけ、掃除を定期に動かす。
   *
   * <p>{@code @EnableScheduling} はアプリのすべての {@code @Scheduled} を動かすため、{@code @Scheduled}
   * はこの条件付きの構成に置き、{@link IdempotencyKeyPurge} には置かない。有効かどうかを決めるのはこの条件だけで、{@code
   * true}（大文字小文字を問わない）だけを真とする。
   */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty("idempotency-key.purge.enabled")
  /* package */ static class Scheduling {

    /** 期限を過ぎた行を消すジョブ。 */
    private final IdempotencyKeyPurge purge;

    /* package */ Scheduling(final IdempotencyKeyPurge purge) {
      this.purge = purge;
    }

    /** 前の回の終わりから {@code interval} を空けて、掃除を 1 回動かす。 */
    @Scheduled(
        fixedDelayString = "${idempotency-key.purge.interval}",
        initialDelayString = "${idempotency-key.purge.interval}")
    /* package */ void purgeOnce() {
      purge.purgeOnce();
    }
  }

  /**
   * 掃除の設定値。
   *
   * @param interval 前の回の終わりから次の回までの間隔
   * @param lockAtMostFor ロックを持つ上限。実行が落ちて解放できなかったとき、この時間を過ぎると他のインスタンスが取れる。{@code interval} より長くする
   * @param batchSize 1 つの DELETE で消す行の上限
   */
  /* package */ @ConfigurationProperties("idempotency-key.purge")
  @Validated
  record Properties(
      @NotNull @DurationMin(seconds = 1) Duration interval,
      @NotNull @DurationMin(seconds = 1) Duration lockAtMostFor,
      @Min(1) int batchSize) {

    /** ロックを持つ下限。再投入のジョブ（ADR-079）と同じく、間隔の半分にする。 */
    /* package */ Duration lockAtLeastFor() {
      return interval.dividedBy(2);
    }

    /** ロックを持つ上限が間隔より長いか。短いと、ロックを持つ下限（間隔の半分）が上限を超えうる。 */
    @AssertTrue(message = "idempotency-key.purge.lock-at-most-for must be longer than interval")
    /* package */ boolean isLockAtMostForLongerThanInterval() {
      return lockAtMostFor == null || interval == null || lockAtMostFor.compareTo(interval) > 0;
    }
  }
}
