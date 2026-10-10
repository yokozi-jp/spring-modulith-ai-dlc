package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.Record5;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 失敗したイベント出版を定期的に再投入するジョブ（ADR-075）。
 *
 * <p>PostgreSQL のセッションの advisory lock を取れたインスタンスだけが、待ち時間を過ぎ、再投入の回数が上限未満の {@code FAILED}
 * の出版を再投入する。上限に達した出版は ERROR のログで知らせ、自動の対象から外す。
 *
 * <p>ロックを持つ間は接続を 1 本固定するため、H1 の規則（{@code tableWritesGoThroughTableWriter}）の例外である。業務テーブルを書かず、 {@code
 * modulith.event_publication} を読むだけにする。
 */
// 集計、ロック、通知、再投入の手順を、接続を固定した 1 つの実行に集めるため、メソッドの数の上限を外す。
@SuppressWarnings("PMD.TooManyMethods")
@Slf4j
@Component
class EventPublicationResubmitter {

  /**
   * advisory lock のキー。アプリで advisory lock を使うのはこのジョブだけである（ADR-075、
   * docs/database/postgresql-concurrency-control.md）。
   */
  /* package */ static final long LOCK_KEY = 108L;

  /** Spring Modulith の失敗した出版の状態。 */
  private static final String FAILED = "FAILED";

  /** ジョブの実行の回数の Counter の名前。 */
  private static final String RUNS_METRIC = "event.publication.resubmission.runs";

  /** 状態の tag の名前。 */
  private static final String STATE = "state";

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** Spring Modulith の失敗した出版の再投入。 */
  private final FailedEventPublications failedEventPublications;

  /** 現在時刻。 */
  private final Clock clock;

  /** 設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /** ロックを取って最後まで実行した回。 */
  private final Counter completedRuns;

  /** 他のインスタンスがロックを持っていて省いた回。 */
  private final Counter skippedRuns;

  /** DB の例外で失敗した回。 */
  private final Counter failedRuns;

  /** 再投入の候補にした出版の数。 */
  private final Counter resubmissions;

  /** 自動の再投入を待つ FAILED の出版の数。 */
  private final AtomicLong retrying = new AtomicLong();

  /** 上限に達した FAILED の出版の数。 */
  private final AtomicLong exhausted = new AtomicLong();

  /** 自動の再投入を待つ最も古い出版の経過秒数。 */
  private final AtomicLong retryingOldestAge = new AtomicLong();

  /** 上限に達した最も古い出版の経過秒数。 */
  private final AtomicLong exhaustedOldestAge = new AtomicLong();

  /** このインスタンスで ERROR を記録した、上限に達した出版の ID。 */
  private final Set<UUID> reported = ConcurrentHashMap.newKeySet();

  /* package */ EventPublicationResubmitter(
      final DSLContext dsl,
      final FailedEventPublications failedEventPublications,
      final Clock clock,
      final MeterRegistry meterRegistry,
      final EventPublicationResubmissionConfig.Properties properties) {
    this.dsl = dsl;
    this.failedEventPublications = failedEventPublications;
    this.clock = clock;
    this.properties = properties;
    this.completedRuns = runs(meterRegistry, "completed");
    this.skippedRuns = runs(meterRegistry, "skipped");
    this.failedRuns = runs(meterRegistry, "failed");
    this.resubmissions =
        Counter.builder("event.publication.resubmissions")
            .description("再投入の候補にした出版の数。ロックの中では再投入した数と一致する")
            .register(meterRegistry);
    // Micrometer は状態のオブジェクトを弱い参照で持つため、final のフィールドを渡す。
    Gauge.builder("event.publication.failed", retrying, AtomicLong::get)
        .tag(STATE, "retrying")
        .register(meterRegistry);
    Gauge.builder("event.publication.failed", exhausted, AtomicLong::get)
        .tag(STATE, "exhausted")
        .register(meterRegistry);
    TimeGauge.builder(
            "event.publication.failed.oldest.age",
            retryingOldestAge,
            TimeUnit.SECONDS,
            AtomicLong::get)
        .tag(STATE, "retrying")
        .register(meterRegistry);
    TimeGauge.builder(
            "event.publication.failed.oldest.age",
            exhaustedOldestAge,
            TimeUnit.SECONDS,
            AtomicLong::get)
        .tag(STATE, "exhausted")
        .register(meterRegistry);
  }

  private static Counter runs(final MeterRegistry meterRegistry, final String outcome) {
    return Counter.builder(RUNS_METRIC).tag("outcome", outcome).register(meterRegistry);
  }

  /** ロックを取れたら、上限に達した出版を知らせ、待ち時間を過ぎた失敗した出版を再投入する。DB の例外では投げずに戻る。 */
  @Scheduled(
      fixedDelayString = "${event-publication.resubmission.interval}",
      initialDelayString = "${event-publication.resubmission.interval}")
  /* package */ void resubmitOnce() {
    try {
      // Spring Boot の構成（ADR-070 の ExecuteListener、例外の変換、Settings）を保ち、接続だけを固定する。
      dsl.connection(connection -> runOn(dsl.configuration().derive(connection).dsl()));
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException e) {
      failedRuns.increment();
      log.atWarn().setCause(e).log("Event publication resubmission failed");
    }
  }

  private void runOn(final DSLContext session) {
    final Instant now = Instant.now(clock);
    updateGauges(session, now);
    // ponytail: セッションの advisory lock は接続の直結が前提。実行の途中でこの接続が切れるとロックが消え、その回の残りは排他されない。
    // その間の重複はリスナーの冪等性に頼る。接続を多重化するプロキシ（RDS Proxy、PgBouncer のトランザクションモード）を入れるときはロックの方式を見直す（ADR-075）。
    if (!tryLock(session)) {
      skippedRuns.increment();
      log.debug("Event publication resubmission skipped");
      return;
    }
    final int exhaustedCount;
    final int resubmitted;
    try {
      exhaustedCount = reportExhausted(session);
      resubmitted = resubmit(now);
    } finally {
      unlock(session);
    }
    // 解放のあとに数え、解放に失敗した回を completed と failed の両方に数えない。
    resubmissions.increment(resubmitted);
    if (resubmitted > 0 || exhaustedCount > 0) {
      log.atInfo()
          .addKeyValue("event_publication.resubmitted.count", resubmitted)
          .addKeyValue("event_publication.exhausted.count", exhaustedCount)
          .log("Event publications resubmitted");
    }
    completedRuns.increment();
  }

  private static boolean tryLock(final DSLContext session) {
    return Boolean.TRUE.equals(
        session.select(advisoryLockFunction("pg_try_advisory_lock")).fetchOne(0, Boolean.class));
  }

  private static void unlock(final DSLContext session) {
    final @Nullable Boolean released =
        session.select(advisoryLockFunction("pg_advisory_unlock")).fetchOne(0, Boolean.class);
    if (!Boolean.TRUE.equals(released)) {
      // ponytail: 偽はこのセッションがロックを持っていない状態。再入で残った回数は数えず、その接続が pool から退く（max-lifetime）までロックが残りうる。
      // 問題になったら、解放に失敗した接続を pool から外す（Connection.abort）拡張をする。
      throw new org.jooq.exception.DataAccessException(
          "pg_advisory_unlock(" + LOCK_KEY + ") returned false");
    }
  }

  private static Field<Boolean> advisoryLockFunction(final String function) {
    return DSL.function(DSL.name(function), Boolean.class, DSL.val(LOCK_KEY));
  }

  private int resubmit(final Instant now) {
    final Instant cutoff = now.minus(properties.waitAge());
    final AtomicInteger candidates = new AtomicInteger();
    // ponytail: LIMIT の後に条件が掛かり、2.1.1 の SQL では withMinAge も FAILED に効かないため、毎回 FAILED
    // の行をすべて読む。行が大量に溜まると毎回の読み込みが重くなる。
    // 上限に達した出版は Runbook で片付ける。Spring Modulith が回数の条件を SQL へ渡せるようになったら LIMIT にする。
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withMinAge(properties.waitAge())
            .withBatchSize(Integer.MAX_VALUE)
            .withFilter(
                publication ->
                    publication.getCompletionAttempts() <= properties.maxResubmissions()
                        && lastAttempt(publication).isBefore(cutoff)
                        && candidates.get() < properties.maxPerRun()
                        && candidates.incrementAndGet() > 0));
    return candidates.get();
  }

  /** 最後の試行の時刻。再投入していなければ最初の試行の時刻。 */
  private static Instant lastAttempt(final EventPublication publication) {
    return Objects.requireNonNullElse(
        publication.getLastResubmissionDate(), publication.getPublicationDate());
  }

  private Condition retryingCondition() {
    return EVENT_PUBLICATION.COMPLETION_ATTEMPTS.le(properties.maxResubmissions());
  }

  private Condition exhaustedCondition() {
    return EVENT_PUBLICATION.COMPLETION_ATTEMPTS.gt(properties.maxResubmissions());
  }

  /** FAILED の出版の件数と最古の経過時間を集計し、Gauge の値にする。 */
  private void updateGauges(final DSLContext session, final Instant now) {
    final Record4<Integer, Instant, Integer, Instant> row =
        session
            .select(
                DSL.count().filterWhere(retryingCondition()),
                DSL.min(EVENT_PUBLICATION.PUBLICATION_DATE).filterWhere(retryingCondition()),
                DSL.count().filterWhere(exhaustedCondition()),
                DSL.min(EVENT_PUBLICATION.PUBLICATION_DATE).filterWhere(exhaustedCondition()))
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.STATUS.eq(FAILED))
            .fetchSingle();
    retrying.set(row.value1());
    retryingOldestAge.set(ageSeconds(row.value2(), now));
    exhausted.set(row.value3());
    exhaustedOldestAge.set(ageSeconds(row.value4(), now));
  }

  private static long ageSeconds(final @Nullable Instant oldest, final Instant now) {
    return oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds());
  }

  /** 上限に達した出版のうち、このインスタンスでまだ記録していないものを ERROR で記録し、上限に達した件数を返す。 */
  private int reportExhausted(final DSLContext session) {
    // serialized_event はイベントの本文なので読まない（ADR-015）。
    final Result<Record5<UUID, String, String, Instant, Integer>> rows =
        session
            .select(
                EVENT_PUBLICATION.ID,
                EVENT_PUBLICATION.EVENT_TYPE,
                EVENT_PUBLICATION.LISTENER_ID,
                EVENT_PUBLICATION.PUBLICATION_DATE,
                EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.STATUS.eq(FAILED).and(exhaustedCondition()))
            .orderBy(EVENT_PUBLICATION.PUBLICATION_DATE)
            .fetch();
    // ponytail: 記録済みの ID はインスタンスのメモリにあるため、同じ出版の ERROR はロックを取ったインスタンスの数と再起動の回数だけ重なりうる。
    // 重複を許さなくなったら、記録済みの ID を持つ表を足す。
    reported.retainAll(rows.stream().map(Record5::value1).collect(Collectors.toSet()));
    int reportedThisRun = 0;
    for (final Record5<UUID, String, String, Instant, Integer> row : rows) {
      if (reportedThisRun >= properties.maxPerRun()) {
        break;
      }
      if (reported.add(row.value1())) {
        reportedThisRun++;
        log.atError()
            .addKeyValue("event_publication.id", row.value1().toString())
            .addKeyValue("event_publication.event_type", row.value2())
            .addKeyValue("event_publication.listener_id", row.value3())
            .addKeyValue("event_publication.completion_attempts", row.value5())
            .log("Event publication resubmission exhausted");
      }
    }
    return rows.size();
  }
}
