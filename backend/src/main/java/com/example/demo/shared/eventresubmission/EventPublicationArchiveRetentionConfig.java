package com.example.demo.shared.eventresubmission;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;

/**
 * 完了したイベント出版の archive の行を保持期限で消す定期の実行と、その設定値を構成する（ADR-120）。
 *
 * <p>Spring Modulith 2.2.0 の {@link CompletedEventPublications#deletePublicationsOlderThan}
 * は、archive の表の完了の時刻が保持期限より古い {@code COMPLETED} の行だけを消し、{@code ABANDONED} の行を残す。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventPublicationArchiveRetentionConfig.Properties.class)
class EventPublicationArchiveRetentionConfig {

  /**
   * 有効なときだけ、archive の行を定期に消す。
   *
   * <p>{@code @Scheduled} を条件付きの構成に置く理由は {@code IdempotencyKeyPurgeConfig.Scheduling} と同じである。
   *
   * <p>同じ条件の DELETE は何度流しても結果が変わらないため、ロックを取らずに各インスタンスで流す。archive
   * の表はモジュールに属さないため、モジュールのロールに切り替えず、{@code app_user} のまま 1 文で消す（#307）。
   *
   * <p>ponytail: DELETE は件数を絞らず、期限を過ぎた行を 1 文ですべて消す。長く止めた後の最初の回は長い 1 文になり、{@code statement_timeout}
   * で失敗しうる。失敗が続いたら、{@code IdempotencyKeyPurge} と同じく {@code batch-size} 行ずつ消す DELETE（ctid と {@code
   * LIMIT}）を自分で書く。
   */
  @Slf4j
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnBooleanProperty("event-publication.archive-retention.enabled")
  /* package */ static class Scheduling {

    /** archive の行を消す Spring Modulith の API。 */
    private final CompletedEventPublications completed;

    /** 保持期限。 */
    private final Duration retention;

    /**
     * 保持期限が、定期の再投入が archive の行を読み戻す期間（ADR-079）より長いことを確かめて作る。
     *
     * @throws IllegalStateException 保持期限が読み戻す期間以下の場合。再投入した出版の結果を数える前に行が消えるため、起動に失敗させる
     */
    /* package */ Scheduling(
        final CompletedEventPublications completed,
        final Properties properties,
        final EventPublicationResubmissionConfig.Properties resubmission) {
      final Duration readBack = resubmission.resubmissionLifetime();
      if (properties.retention().compareTo(readBack) <= 0) {
        throw new IllegalStateException(
            "event-publication.archive-retention.retention ("
                + properties.retention()
                + ") must be longer than the archive read-back period of the resubmission ("
                + readBack
                + ")");
      }
      this.completed = completed;
      this.retention = properties.retention();
    }

    /**
     * 起動の 5 分後と、前の回の終わりから {@code interval} を空けて、保持期限を過ぎた行を消す。投げずに戻る。
     *
     * <p>DB の失敗は WARN で、予期しない実行時の例外は ERROR で記録する。どちらも次の回に同じ行を消し直す。
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // 初回を interval（1 日）にすると、1 日に 1 回より多く再起動するインスタンスでは一度も動かない。DELETE は冪等なので起動ごとに流してよい。
    @Scheduled(
        fixedDelayString = "${event-publication.archive-retention.interval}",
        initialDelayString = "PT5M")
    /* package */ void deleteExpired() {
      try {
        completed.deletePublicationsOlderThan(retention);
      } catch (DataAccessException e) {
        log.atWarn().setCause(e).log("Event publication archive retention failed");
      } catch (RuntimeException e) {
        log.atError().setCause(e).log("Event publication archive retention crashed");
      }
    }
  }

  /**
   * archive の行の保持期限の設定値。
   *
   * @param retention {@code COMPLETED} の行を残す期間。完了の時刻から測る。定期の再投入が archive を読み戻す期間より長くする
   * @param interval 前の回の終わりから次の回までの間隔
   */
  /* package */ @ConfigurationProperties("event-publication.archive-retention")
  @Validated
  record Properties(
      @NotNull @DurationMin(seconds = 1) Duration retention,
      @NotNull @DurationMin(seconds = 1) Duration interval) {}
}
