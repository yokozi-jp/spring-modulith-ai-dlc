package com.example.demo.shared.persistence;

import static com.example.demo.jooq.ordering.Tables.T_IDEMPOTENCY_KEY;
import static com.example.demo.jooq.tables.FixtureIdempotencyKeyTable.FIXTURE_IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotencyKeyException;
import com.example.demo.shared.idempotency.IdempotentCommand;
import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.FixtureTablesExtension;
import com.example.demo.testkit.IdempotencyKeys;
import errorfixture.presentation.web.ErrorFixtureFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link JooqIdempotency} が、ordering のキーの表で、同じキーの再送に保存した Result を返すことを、時刻を固定して確かめる（ADR-126）。
 *
 * <p>各テストはロールバックする。同じトランザクションの中で呼ぶので、自分の入れた行は待たずに見える。テナントの分け方は、RLS のない fixture の表でも確かめる。
 */
// 処理ごとの値と失敗の説明は、テストごとに読み下せるよう、定数にせず文字列のまま書く。
// 呼び出しの束縛（テナント、ロール、利用者、操作の名前）を補助のメソッドにまとめるため、メソッドの数の上限を外す。
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
@DatabaseTest
@ExtendWith(FixtureTablesExtension.class)
class JooqIdempotencyTest {

  /** 1 回目の要求の時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-04T01:02:03.123456Z");

  /** 1 回目の要求で書いたキーの期限。 */
  private static final Instant EXPIRES_AT = NOW.plus(Duration.ofHours(24));

  /** ordering のキーの表を使う操作の名前。 */
  private static final String PGM_CD = "ordering.IdempotencyTest";

  /** テストの利用者でない、別の利用者。 */
  private static final String OTHER_USER = "6a1c3e5f-7b9d-4f1a-8c2e-4d6f8a0b2c4e";

  /** テストの利用者のテナントでない、別のテナント。 */
  private static final TenantId OTHER_TENANT =
      new TenantId(UUID.fromString("0f4b7c2a-6d1e-4a8b-9c3d-5e6f7a8b9c0d"));

  /** テストのトランザクションの jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 業務の処理を呼んだ回数。 */
  private final AtomicInteger runs = new AtomicInteger();

  @Test
  @DisplayName("新しいキーは処理を実行し、同じキーと同じペイロードの再送は処理を実行せずに保存した Result を返す")
  void replaysStoredResultForSamePayload() {
    final IdempotencyKey key = IdempotencyKeys.next();

    final Result first = execute(NOW, new Order("A", key), "first");
    final Result replayed = execute(NOW, new Order("A", key), "second");

    assertThat(replayed).as("key=%s の再送の Result", key.value()).isEqualTo(first);
    assertThat(first).isEqualTo(new Result("first"));
    assertThat(runs).as("key=%s の処理の回数", key.value()).hasValue(1);
  }

  @Test
  @DisplayName("有効なキーを違うペイロードに使うと不一致の例外になり、処理を実行しない")
  void rejectsDifferentPayload() {
    final IdempotencyKey key = IdempotencyKeys.next();
    execute(NOW, new Order("A", key), "first");

    assertThatThrownBy(() -> execute(NOW, new Order("B", key), "second"))
        .isInstanceOfSatisfying(
            IdempotencyKeyException.class,
            e -> assertThat(e.reason()).isEqualTo(IdempotencyKeyException.Reason.PAYLOAD_MISMATCH));
    assertThat(runs).as("key=%s の処理の回数", key.value()).hasValue(1);
  }

  @Test
  @DisplayName("期限の 1 マイクロ秒前までは有効なキーとして扱い、違うペイロードを不一致にする")
  void keyIsLiveUntilJustBeforeExpiry() {
    final IdempotencyKey key = IdempotencyKeys.next();
    execute(NOW, new Order("A", key), "first");

    assertThatThrownBy(() -> execute(EXPIRES_AT.minusNanos(1_000), new Order("B", key), "second"))
        .isInstanceOf(IdempotencyKeyException.class);
  }

  @Test
  @DisplayName("期限を迎えた行は新しいキーとして取り直し、新しいペイロードで処理を実行して、その Result を保存し、作成の時刻も取り直した時刻にする")
  void expiredKeyIsTreatedAsNew() {
    final IdempotencyKey key = IdempotencyKeys.next();
    execute(NOW, new Order("A", key), "first");

    final Result taken = execute(EXPIRES_AT, new Order("B", key), "second");
    final Result replayed = execute(EXPIRES_AT.plusSeconds(1), new Order("B", key), "third");

    assertThat(taken).as("key=%s の期限の後の Result", key.value()).isEqualTo(new Result("second"));
    assertThat(replayed).as("key=%s の取り直した後の再送", key.value()).isEqualTo(taken);
    assertThat(runs).as("key=%s の処理の回数", key.value()).hasValue(2);
    final Record2<Instant, Instant> audit =
        AsModule.call(
            dsl,
            "ordering",
            tx ->
                tx.select(T_IDEMPOTENCY_KEY.CREATED_AT, T_IDEMPOTENCY_KEY.EXPIRES_AT)
                    .from(T_IDEMPOTENCY_KEY)
                    .where(T_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY.eq(key.value()))
                    .fetchSingle());
    assertThat(audit.value1()).as("key=%s の取り直した行の created_at", key.value()).isEqualTo(EXPIRES_AT);
    assertThat(audit.value2())
        .as("key=%s の取り直した行の expires_at", key.value())
        .isEqualTo(EXPIRES_AT.plus(Duration.ofHours(24)));
  }

  @Test
  @DisplayName("別の利用者と別のテナントは、同じキーでも互いの行に当たらない")
  void usersAndTenantsDoNotShareKeys() {
    final IdempotencyKey key = IdempotencyKeys.next();
    execute(NOW, AsTenant.TENANT, AppUsers.USER_ID, new Order("A", key), "test user");

    final Result otherUser =
        execute(NOW, AsTenant.TENANT, OTHER_USER, new Order("B", key), "other user");
    final Result otherTenant =
        execute(NOW, OTHER_TENANT, AppUsers.USER_ID, new Order("C", key), "other tenant");

    assertThat(otherUser).as("key=%s の別の利用者", key.value()).isEqualTo(new Result("other user"));
    assertThat(otherTenant).as("key=%s の別のテナント", key.value()).isEqualTo(new Result("other tenant"));
    assertThat(runs).as("key=%s の処理の回数", key.value()).hasValue(3);
  }

  @Test
  @DisplayName("RLS のない表でも、別のテナントの同じ利用者と同じキーの行に当たらず、それぞれの Result を返す")
  void tenantsAreSeparatedWithoutRowLevelSecurity() {
    // ordering の表は RLS も別のテナントの行を隠すので、RLS も既定値もない fixture の表で、文が書くテナントだけで分かれることを確かめる。
    final IdempotencyKey key = IdempotencyKeys.next();
    final Result first = executeWithoutRls(AsTenant.TENANT, new Order("A", key), "tenant");
    final Result other = executeWithoutRls(OTHER_TENANT, new Order("A", key), "other tenant");

    assertThat(executeWithoutRls(AsTenant.TENANT, new Order("A", key), "replayed"))
        .as("key=%s のテナントの再送", key.value())
        .isEqualTo(first);
    assertThat(executeWithoutRls(OTHER_TENANT, new Order("A", key), "replayed"))
        .as("key=%s の別のテナントの再送", key.value())
        .isEqualTo(other);
    assertThat(other).isEqualTo(new Result("other tenant"));
    assertThat(runs).as("key=%s の処理の回数", key.value()).hasValue(2);
  }

  @Test
  @DisplayName("処理が例外で終わるとキーの行を残さず、同じキーで直したペイロードを送ると処理を実行する")
  void failedOperationLeavesNoKey() {
    final IdempotencyKey key = IdempotencyKeys.next();
    assertThatThrownBy(() ->
            execute(
                NOW,
                AsTenant.TENANT,
                AppUsers.USER_ID,
                new Order("A", key),
                Result.class,
                () -> {
                  throw new BusinessRuleViolationException(
                      ErrorFixtureFailure.REJECTED, "rejected");
                }))
        .isInstanceOf(BusinessRuleViolationException.class);

    assertThat(execute(NOW, new Order("B", key), "fixed"))
        .as("key=%s の失敗の後の Result", key.value())
        .isEqualTo(new Result("fixed"));
  }

  @Test
  @DisplayName("保存した Result に今の Result の型にないプロパティがあっても、読み直す")
  void storedResultWithUnknownPropertyIsReadable() {
    final IdempotencyKey key = IdempotencyKeys.next();
    execute(
        NOW,
        AsTenant.TENANT,
        AppUsers.USER_ID,
        new Order("A", key),
        OlderResult.class,
        () -> new OlderResult("first", "removed later"));

    assertThat(execute(NOW, new Order("A", key), "second"))
        .as("key=%s の、プロパティの減った型での再送", key.value())
        .isEqualTo(new Result("first"));
  }

  /** テストの利用者とテナントで、値を Result にする処理を呼ぶ。 */
  private Result execute(final Instant now, final Order command, final String value) {
    return execute(now, AsTenant.TENANT, AppUsers.USER_ID, command, value);
  }

  private Result execute(
      final Instant now,
      final TenantId tenant,
      final String userId,
      final Order command,
      final String value) {
    return execute(
        now,
        tenant,
        userId,
        command,
        Result.class,
        () -> {
          runs.incrementAndGet();
          return new Result(value);
        });
  }

  /** 時刻を固定した検証対象を、ordering のロールとテナント、利用者、操作の名前を束縛して呼ぶ。 */
  private <R> R execute(
      final Instant now,
      final TenantId tenant,
      final String userId,
      final Order command,
      final Class<R> resultType,
      final Supplier<R> operation) {
    return TenantContext.call(
        tenant,
        () ->
            AsModule.call(
                dsl,
                "ordering",
                tenant,
                tx ->
                    AppUsers.signedIn(
                        userId,
                        () ->
                            ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD)
                                .call(() ->
                                    new JooqIdempotency(
                                            tx,
                                            TestCommonColumns.at(now),
                                            Clock.fixed(now, ZoneOffset.UTC))
                                        .execute(command, resultType, operation)))));
  }

  /** RLS のない fixture のキーの表で、テナントを束縛して呼ぶ。DB のテナントの設定はテストのトランザクションのままにする。 */
  private Result executeWithoutRls(final TenantId tenant, final Order command, final String value) {
    final JooqIdempotency idempotency =
        new JooqIdempotency(
            dsl,
            TestCommonColumns.at(NOW),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Map.of("fixture", IdempotencyKeyTable.from(FIXTURE_IDEMPOTENCY_KEY)));
    return TenantContext.call(
        tenant,
        () ->
            AppUsers.signedIn(() ->
                ScopedValue.where(PgmCdAspect.PGM_CD, "fixture.IdempotencyTest")
                    .call(() ->
                        idempotency.execute(
                            command,
                            Result.class,
                            () -> {
                              runs.incrementAndGet();
                              return new Result(value);
                            }))));
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

  /**
   * 前の配備の Result。今の {@link Result} にないプロパティを持つ。
   *
   * @param value 処理ごとの値
   * @param extra 今の型からは消したプロパティ
   */
  /* package */ record OlderResult(String value, String extra) {}
}
