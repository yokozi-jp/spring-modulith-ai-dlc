package com.example.demo.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.ordering.Tables;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotentCommand;
import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.CommittedDatabaseTest;
import com.example.demo.testkit.IdempotencyKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link IdempotencyKeyPurge} が、期限を迎えた {@code Idempotency-Key} の行を、ordering はテナントごとに、tenancy
 * は全テナントの行を消し、期限の前の行を残すことを、時刻を固定して確かめる（ADR-126）。
 *
 * <p>ジョブはモジュールのロールのトランザクションを自分で始めるので、コミットする。1 つの DELETE の上限を 1 行にして、消し残しを同じ回で繰り返し消すことも確かめる。
 */
@CommittedDatabaseTest
class IdempotencyKeyPurgeTest {

  /** 1 回目の要求の時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-04T01:02:03.123456Z");

  /** {@link #NOW} に書いたキーの期限。掃除はこの時刻に動かす。 */
  private static final Instant EXPIRES_AT = NOW.plus(Duration.ofHours(24));

  /** 期限まで 1 マイクロ秒残るキーを書く時刻。 */
  private static final Instant JUST_AFTER = NOW.plusNanos(1_000);

  /** 1 つの DELETE で消す行の上限。消し残しを同じ回で繰り返し消すことを確かめるため 1 にする。 */
  private static final IdempotencyKeyPurgeConfig.Properties PROPERTIES =
      new IdempotencyKeyPurgeConfig.Properties(Duration.ofSeconds(2), Duration.ofMinutes(1), 1);

  /** ordering のキーの表を持つモジュール。 */
  private static final String ORDERING = "ordering";

  /** RLS の外のキーの表を持つモジュール。 */
  private static final String TENANCY = "tenancy";

  /** 行を書いて読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** モジュールのロールに切り替えるトランザクションマネージャー。 */
  @Autowired private PlatformTransactionManager transactionManager;

  /** ADR-079 と共有するロックの実行。 */
  @Autowired private LockingTaskExecutor lockingTaskExecutor;

  /** 他のインスタンスの代わりにロックを持つ。 */
  @Autowired private LockProvider lockProvider;

  /** 実行の回数と消した行の数を読むための、このテストだけのレジストリ。 */
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  /** 業務の処理を呼んだ回数。 */
  private final AtomicInteger operations = new AtomicInteger();

  /** 掃除するテナント。 */
  private TenantId tenantA;

  /** 掃除するもう 1 つのテナント。 */
  private TenantId tenantB;

  @BeforeEach
  void registerTenants() {
    tenantA = new TenantId(TestTenancy.tenant(dsl, "テナント A"));
    tenantB = new TenantId(TestTenancy.tenant(dsl, "テナント B"));
  }

  @Test
  @DisplayName("期限を迎えた行を ordering は各テナントで、tenancy は全テナントで消し、期限まで 1 マイクロ秒残る行を残す")
  void purgesExpiredRowsAndKeepsLiveRows() {
    final IdempotencyKey liveInOrdering = IdempotencyKeys.next();
    final IdempotencyKey liveInTenancy = IdempotencyKeys.next();
    write(ORDERING, tenantA, NOW, IdempotencyKeys.next());
    write(ORDERING, tenantA, NOW, IdempotencyKeys.next());
    write(ORDERING, tenantB, NOW, IdempotencyKeys.next());
    write(ORDERING, tenantA, JUST_AFTER, liveInOrdering);
    write(TENANCY, tenantA, NOW, IdempotencyKeys.next());
    write(TENANCY, tenantB, NOW, IdempotencyKeys.next());
    write(TENANCY, tenantB, JUST_AFTER, liveInTenancy);

    purge().purgeOnce();

    assertThat(orderingKeys(tenantA))
        .as("テナント A の ordering の行")
        .containsExactly(liveInOrdering.value());
    assertThat(orderingKeys(tenantB)).as("テナント B の ordering の行").isEmpty();
    assertThat(tenancyKeys()).as("tenancy の全テナントの行").containsExactly(liveInTenancy.value());
    assertThat(meterRegistry.get("idempotency.key.purged").counter().count()).isEqualTo(5.0);
    assertThat(runs("completed")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("消したキーは、違うペイロードでも新しいキーとして処理を実行する")
  void purgedKeyIsTreatedAsNew() {
    final IdempotencyKey key = IdempotencyKeys.next();
    write(ORDERING, tenantA, NOW, key, "A");
    purge().purgeOnce();
    assertThat(orderingKeys(tenantA)).as("掃除の後の行").isEmpty();

    final Result taken = write(ORDERING, tenantA, EXPIRES_AT, key, "B");

    assertThat(taken).as("key=%s の掃除の後の Result", key.value()).isEqualTo(new Result("B"));
    assertThat(operations).as("key=%s の処理の回数", key.value()).hasValue(2);
  }

  @Test
  @DisplayName("他のインスタンスがロックを持つ間は行を消さず、skipped を 1 だけ増やす")
  void skipsWhileAnotherInstanceHoldsLock() {
    final IdempotencyKey key = IdempotencyKeys.next();
    write(ORDERING, tenantA, NOW, key);
    final SimpleLock held =
        lockProvider
            .lock(
                new LockConfiguration(
                    EXPIRES_AT,
                    IdempotencyKeyPurge.LOCK_NAME,
                    Duration.ofMinutes(1),
                    Duration.ZERO))
            .orElseThrow(() -> new AssertionError("テストのロックを取れない"));
    try {
      purge().purgeOnce();
    } finally {
      held.unlock();
    }

    assertThat(orderingKeys(tenantA)).as("ロックの間の行").containsExactly(key.value());
    assertThat(runs("skipped")).isEqualTo(1.0);
    assertThat(runs("completed")).isZero();
  }

  /** 期限の時刻に動かす検証対象。 */
  private IdempotencyKeyPurge purge() {
    return new IdempotencyKeyPurge(
        dsl,
        transactionManager,
        lockingTaskExecutor,
        Clock.fixed(EXPIRES_AT, ZoneOffset.UTC),
        meterRegistry,
        PROPERTIES);
  }

  private double runs(final String outcome) {
    return meterRegistry
        .get("idempotency.key.purge.runs")
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  private void write(
      final String module, final TenantId tenant, final Instant now, final IdempotencyKey key) {
    write(module, tenant, now, key, "A");
  }

  /** モジュールのロールとテナント、テストの利用者、操作の名前を束縛して、時刻 {@code now} にキーを書く。 */
  private Result write(
      final String module,
      final TenantId tenant,
      final Instant now,
      final IdempotencyKey key,
      final String code) {
    return TenantContext.call(
        tenant,
        () ->
            AsModule.call(
                dsl,
                module,
                tenant,
                tx ->
                    AppUsers.signedIn(() ->
                        ScopedValue.where(PgmCdAspect.PGM_CD, module + ".PurgeTest")
                            .call(() ->
                                new JooqIdempotency(
                                        tx,
                                        TestCommonColumns.at(now),
                                        Clock.fixed(now, ZoneOffset.UTC))
                                    .execute(
                                        new Order(code, key),
                                        Result.class,
                                        () -> {
                                          operations.incrementAndGet();
                                          return new Result(code);
                                        })))));
  }

  /** テナントの ordering のキーを読む。 */
  private List<UUID> orderingKeys(final TenantId tenant) {
    return AsModule.call(
        dsl,
        ORDERING,
        tenant,
        tx ->
            tx.select(Tables.T_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY)
                .from(Tables.T_IDEMPOTENCY_KEY)
                .fetch(Tables.T_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY));
  }

  /** 全テナントの tenancy のキーを読む。 */
  private List<UUID> tenancyKeys() {
    final com.example.demo.jooq.tenancy.tables.TIdempotencyKey keys =
        com.example.demo.jooq.tenancy.Tables.T_IDEMPOTENCY_KEY;
    return AsModule.callWithoutTenant(
        dsl, TENANCY, tx -> tx.select(keys.IDEMPOTENCY_KEY).from(keys).fetch(keys.IDEMPOTENCY_KEY));
  }

  /**
   * テストの Command。
   *
   * @param code ペイロードを変える値
   * @param idempotencyKey キー
   */
  /* package */ record Order(String code, IdempotencyKey idempotencyKey)
      implements IdempotentCommand {}

  /**
   * テストの Result。
   *
   * @param value 処理ごとの値
   */
  /* package */ record Result(String value) {}
}
