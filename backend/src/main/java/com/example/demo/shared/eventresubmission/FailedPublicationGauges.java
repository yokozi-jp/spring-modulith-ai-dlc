package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.EventPublication;

/**
 * 失敗した出版の件数と最古の経過時間の Gauge（{@code event.publication.failed} と {@code
 * event.publication.failed.oldest.age}）を集計し、回数の上限を超えた {@code FAILED} の出版が現れたら ERROR を出す（ADR-079）。
 *
 * <p>{@code state} の値は、{@code retrying} が回数の上限の内の {@code FAILED}、{@code over-limit} が上限を超えた {@code
 * FAILED}、{@code exhausted} が archive の表の {@code ABANDONED} の出版である。
 */
@Slf4j
final class FailedPublicationGauges {

  /** 状態の tag の名前。 */
  private static final String STATE = "state";

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** 再投入の回数の上限。 */
  private final EventPublicationResubmissionConfig.AbandonProperties abandon;

  /** 自動の再投入を待つ FAILED の出版。 */
  private final StateGauges retrying;

  /** 失敗を記録しない試行で回数の上限を超え、FAILED に残った出版。 */
  private final StateGauges overLimit;

  /** 上限に達した ABANDONED の出版。 */
  private final StateGauges exhausted;

  /* package */ FailedPublicationGauges(
      final DSLContext dsl,
      final MeterRegistry meterRegistry,
      final EventPublicationResubmissionConfig.AbandonProperties abandon) {
    this.dsl = dsl;
    this.abandon = abandon;
    this.retrying = StateGauges.register(meterRegistry, "retrying");
    this.overLimit = StateGauges.register(meterRegistry, "over-limit");
    this.exhausted = StateGauges.register(meterRegistry, "exhausted");
  }

  /**
   * 件数と最古の経過時間を集計して Gauge の値にし、上限を超えた {@code FAILED} の出版の件数が 0 から増えたときだけ、件数の ERROR を 1 つ出す。
   *
   * <p>DB の既知の失敗は WARN で記録し、Gauge は前の値のまま戻る。 Gauge は表示と警報のためで、再投入の前提ではないためである。
   */
  /* package */ void refresh(final Instant now) {
    try {
      final FailedCounts failed = countFailed();
      retrying.update(failed.retrying(), failed.oldestRetrying(), now);
      // 定期の実行は fixedDelay で重ならないため、前の件数を読んでから書く間に他の集計は入らない。
      final boolean appeared = overLimit.isZero() && failed.overLimit() > 0;
      overLimit.update(failed.overLimit(), failed.oldestOverLimit(), now);
      if (appeared) {
        // 失敗を記録しない試行で上限を超えた出版は ABANDONED にならず、出版ごとの ERROR も出ない。
        // 0 から増えたときだけ知らせ、残っている間は Gauge の警報に任せる。
        log.atError()
            .addKeyValue("event_publication.over_limit.count", failed.overLimit())
            .log("Failed event publications exceed the resubmission limit");
      }
      // 条件を部分索引 event_publication_archive_abandoned_idx の述語と同じにし、archive の全行を読まない。
      final StateCount abandoned =
          dsl.select(DSL.count(), DSL.min(EVENT_PUBLICATION_ARCHIVE.PUBLICATION_DATE))
              .from(EVENT_PUBLICATION_ARCHIVE)
              .where(EVENT_PUBLICATION_ARCHIVE.STATUS.eq(EventPublication.Status.ABANDONED.name()))
              .fetchSingle(Records.mapping(StateCount::new));
      exhausted.update(abandoned.count(), abandoned.oldest(), now);
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException e) {
      log.atWarn().setCause(e).log("Event publication gauges refresh failed");
    }
  }

  /** FAILED の出版を、1 つの文で回数の上限の内と外に分けて数える。 */
  private FailedCounts countFailed() {
    // AbandonProperties.exceeded と同じ規則。Spring Modulith と同じく、空の completion_attempts は 0 と読む。
    final Condition overLimitRow =
        DSL.coalesce(EVENT_PUBLICATION.COMPLETION_ATTEMPTS, 0).gt(abandon.abandonAfterAttempts());
    return dsl.select(
            DSL.count().filterWhere(DSL.not(overLimitRow)),
            DSL.min(EVENT_PUBLICATION.PUBLICATION_DATE).filterWhere(DSL.not(overLimitRow)),
            DSL.count().filterWhere(overLimitRow),
            DSL.min(EVENT_PUBLICATION.PUBLICATION_DATE).filterWhere(overLimitRow))
        .from(EVENT_PUBLICATION)
        .where(EVENT_PUBLICATION.STATUS.eq(EventPublication.Status.FAILED.name()))
        .fetchSingle(Records.mapping(FailedCounts::new));
  }

  /**
   * 回数の上限の内と外の FAILED の出版の件数と、最も古い出版の時刻。
   *
   * @param retrying 上限の内の件数
   * @param oldestRetrying 上限の内で最も古い出版の時刻。なければ {@code null}
   * @param overLimit 上限を超えた件数
   * @param oldestOverLimit 上限を超えた中で最も古い出版の時刻。なければ {@code null}
   */
  private record FailedCounts(
      int retrying,
      @Nullable Instant oldestRetrying,
      int overLimit,
      @Nullable Instant oldestOverLimit) {}

  /**
   * 1 つの状態の件数と、最も古い出版の時刻。
   *
   * @param count 件数
   * @param oldest 最も古い出版の時刻。なければ {@code null}
   */
  private record StateCount(int count, @Nullable Instant oldest) {}

  /**
   * 1 つの {@code state} の、件数と最古の経過秒数の Gauge。
   *
   * <p>Micrometer は状態のオブジェクトを弱い参照で持つため、final のフィールドで持つ。
   *
   * @param count 件数
   * @param oldestAge 最も古い出版の経過秒数
   */
  private record StateGauges(AtomicLong count, AtomicLong oldestAge) {

    private static StateGauges register(final MeterRegistry meterRegistry, final String state) {
      final StateGauges gauges = new StateGauges(new AtomicLong(), new AtomicLong());
      Gauge.builder("event.publication.failed", gauges.count, AtomicLong::get)
          .tag(STATE, state)
          .register(meterRegistry);
      TimeGauge.builder(
              "event.publication.failed.oldest.age",
              gauges.oldestAge,
              TimeUnit.SECONDS,
              AtomicLong::get)
          .tag(STATE, state)
          .register(meterRegistry);
      return gauges;
    }

    private boolean isZero() {
      return count.get() == 0;
    }

    /** 件数と最も古い出版の時刻から値を決める。時計が出版より前なら経過秒数を 0 にする。 */
    private void update(final int newCount, final @Nullable Instant oldest, final Instant now) {
      count.set(newCount);
      oldestAge.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds()));
    }
  }
}
