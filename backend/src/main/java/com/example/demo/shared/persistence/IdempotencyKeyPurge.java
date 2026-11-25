package com.example.demo.shared.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_TENANT;

import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.shared.tenancy.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntUnaryOperator;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.support.LockException;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 期限を過ぎた {@code Idempotency-Key} の行を消す定期のジョブ（ADR-126）。
 *
 * <p>ロックの形は、失敗したイベント出版の定期の再投入（ADR-079）と同じである。ロックの表と provider を共有し、ロックの名前で分ける。ロックを取れたインスタンスだけが、jOOQ
 * のカタログから引いたモジュールごとのキーの表を、そのモジュールのロールのトランザクションで消す。RLS で分けるモジュールの表は、{@code tenancy.m_tenant} のテナントを
 * 1 つずつ束縛して消す。RLS の外の {@code tenancy} の表は、テナントを束縛せずに全テナントの行を消す。
 *
 * <p>1 つの DELETE は {@code batch-size} 行までにして、トランザクションを短く保つ。消し残しがあれば、同じ回で繰り返す。消した行は、DELETE を commit
 * するたびに数える。
 *
 * <p>1 つのテナントの表の失敗で、後ろのテナントとモジュールの掃除を止めない。失敗は集めて、回の最後に 1 つとして投げる。
 *
 * <p>ponytail: 回の時間に予算を置かない。回が {@code lock-at-most-for} を超えると別のインスタンスも同じ表を消しうるが、DELETE
 * はロックした期限切れの行だけを消すので、重なっても結果は同じである。
 *
 * <p>ponytail: 1 回の実行は、RLS のキーの表を持つモジュール数 × テナント数のトランザクションを、期限切れの行がなくても開く。 キーの表に {@code expires_at}
 * の索引はなく、{@code tenancy} の表は全行を読む。24 時間分の行しかない間は許す。 テナントが数千を超えるか、実行が {@code interval} に近づいたら、{@code
 * expires_at} の索引を足し、期限切れの行があるテナントだけを回す。
 */
@Slf4j
@Component
class IdempotencyKeyPurge {

  /** ロックの名前。 */
  /* package */ static final String LOCK_NAME = "idempotency-key-purge";

  /** ジョブの実行の回数の Counter の名前。 */
  private static final String RUNS_METRIC = "idempotency.key.purge.runs";

  /** トランザクションの名前の末尾。 */
  private static final String OPERATION = IdempotencyKeyPurge.class.getSimpleName();

  /** テナントの一覧を持つモジュール。 */
  private static final String TENANT_MODULE =
      Objects.requireNonNull(M_TENANT.getSchema()).getName();

  /** 期限を過ぎた行を消し、テナントを読む jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** モジュールのロールのトランザクションを始める。 */
  private final PlatformTransactionManager transactionManager;

  /** ロックの実行。 */
  private final LockingTaskExecutor lockingTaskExecutor;

  /** 期限と比べる現在時刻。 */
  private final Clock clock;

  /** 設定値。 */
  private final IdempotencyKeyPurgeConfig.Properties properties;

  /** モジュールの名前からキーの表への対応。名前の順に消す。 */
  private final Map<String, IdempotencyKeyTable> tables;

  /** ロックを取って最後まで実行した回。 */
  private final Counter completedRuns;

  /** 他のインスタンスがロックを持っていて省いた回。 */
  private final Counter skippedRuns;

  /** 例外で終わった回。 */
  private final Counter failedRuns;

  /** 消した行の数。 */
  private final Counter purged;

  /**
   * jOOQ のカタログのキーの表で作る。
   *
   * @throws IllegalArgumentException キーの表に列がない、または型が異なる場合
   */
  /* package */ IdempotencyKeyPurge(
      final DSLContext dsl,
      final PlatformTransactionManager transactionManager,
      final LockingTaskExecutor lockingTaskExecutor,
      final Clock clock,
      final MeterRegistry meterRegistry,
      final IdempotencyKeyPurgeConfig.Properties properties) {
    this.dsl = dsl;
    this.transactionManager = transactionManager;
    this.lockingTaskExecutor = lockingTaskExecutor;
    this.clock = clock;
    this.properties = properties;
    this.tables = new TreeMap<>(IdempotencyKeyTable.inCatalog());
    this.completedRuns = runs(meterRegistry, "completed");
    this.skippedRuns = runs(meterRegistry, "skipped");
    this.failedRuns = runs(meterRegistry, "failed");
    this.purged =
        Counter.builder("idempotency.key.purged")
            .description("期限を過ぎて消した Idempotency-Key の行の数")
            .register(meterRegistry);
  }

  private static Counter runs(final MeterRegistry meterRegistry, final String outcome) {
    return Counter.builder(RUNS_METRIC).tag("outcome", outcome).register(meterRegistry);
  }

  /**
   * ロックを取れたら、期限を過ぎた行を消す。投げずに戻る。
   *
   * <p>DB やロックの既知の失敗は WARN で、予期しない実行時の例外は ERROR で記録し、どちらも {@code failed} の回に数える。
   */
  // スケジューラの既定のエラーハンドラに例外を漏らすと、runs のどの tag も動かず停止に気づけないため、予期しない実行時の例外も受けて数える。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  /* package */ void purgeOnce() {
    try {
      run();
    } catch (org.springframework.dao.DataAccessException
        | org.jooq.exception.DataAccessException
        | LockException e) {
      failedRuns.increment();
      log.atWarn().setCause(e).log("Idempotency key purge failed");
    } catch (RuntimeException e) {
      failedRuns.increment();
      log.atError().setCause(e).log("Idempotency key purge crashed");
    }
  }

  /** ロックを取れたら消し、回の結果を数える。表またはテナントの失敗があれば、最初の失敗に残りを付けて投げる。 */
  private void run() {
    final Instant now = Instant.now(clock);
    final AtomicBoolean locked = new AtomicBoolean();
    final List<RuntimeException> failures = new ArrayList<>();
    lockingTaskExecutor.executeWithLock(
        (Runnable)
            () -> {
              locked.set(true);
              purgeAll(now, failures);
            },
        new LockConfiguration(
            now, LOCK_NAME, properties.lockAtMostFor(), properties.lockAtLeastFor()));
    if (!locked.get()) {
      skippedRuns.increment();
      log.debug("Idempotency key purge skipped");
      return;
    }
    if (!failures.isEmpty()) {
      final RuntimeException first = failures.getFirst();
      failures.stream().skip(1).forEach(first::addSuppressed);
      throw first;
    }
    completedRuns.increment();
  }

  /** すべてのモジュールのキーの表を、名前の順に消す。表またはテナントごとの失敗は {@code failures} に集めて続ける。 */
  private void purgeAll(final Instant now, final List<RuntimeException> failures) {
    final List<TenantId> tenants = tenants();
    tables.forEach((module, keys) -> {
      if (ModuleRoles.TENANT_SCOPED_MODULES.contains(module)) {
        for (final TenantId tenant : tenants) {
          // ロールとテナントはトランザクションマネージャーが設定し、文もテナントを条件に入れる（ADR-126）。
          isolate(
              failures,
              () ->
                  TenantContext.call(
                      tenant,
                      () -> {
                        deleteInBatches(
                            module,
                            limit ->
                                IdempotencyKeyWriter.deleteExpiredInTenant(
                                    dsl, keys, tenant.value(), now, limit));
                        return null;
                      }));
        }
      } else {
        isolate(
            failures,
            () ->
                deleteInBatches(
                    module,
                    limit ->
                        IdempotencyKeyWriter.deleteExpiredInAllTenants(dsl, keys, now, limit)));
      }
    });
  }

  // 1 つの表の失敗で後ろの表の掃除を止めないため、予期しない実行時の例外も集める。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static void isolate(final List<RuntimeException> failures, final Runnable purge) {
    try {
      purge.run();
    } catch (RuntimeException e) {
      failures.add(e);
    }
  }

  /** 消し残しがなくなるまで、{@code batch-size} 行ずつ、1 つずつのトランザクションで消し、commit のたびに数える。 */
  private void deleteInBatches(final String module, final IntUnaryOperator deleteUpTo) {
    final TransactionTemplate transaction = transactionIn(module);
    final int limit = properties.batchSize();
    int rows;
    do {
      rows = Objects.requireNonNull(transaction.execute(status -> deleteUpTo.applyAsInt(limit)));
      purged.increment(rows);
    } while (rows == limit);
  }

  /** 全テナントの {@code public_id} を、{@code tenancy} のロールで読む。 */
  private List<TenantId> tenants() {
    final TransactionTemplate transaction = transactionIn(TENANT_MODULE);
    transaction.setReadOnly(true);
    return Objects.requireNonNull(
        transaction.execute(status ->
            dsl.select(M_TENANT.PUBLIC_ID)
                .from(M_TENANT)
                .orderBy(M_TENANT.PUBLIC_ID)
                .fetch(row -> new TenantId(row.value1()))));
  }

  /** モジュールのロールで動くトランザクション（ADR-094）。 */
  private TransactionTemplate transactionIn(final String module) {
    final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setName(ModuleRoles.transactionNameIn(module, OPERATION));
    return transaction;
  }
}
