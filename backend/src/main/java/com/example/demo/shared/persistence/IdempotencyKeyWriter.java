package com.example.demo.shared.persistence;

import com.example.demo.shared.concurrency.ConflictException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * モジュールのスキーマの {@code Idempotency-Key} の行を書き、期限を過ぎた行を消す（ADR-126）。{@link JooqIdempotency} と {@link
 * IdempotencyKeyPurge} だけが使う。
 *
 * <p>共通カラムと SQLSTATE の変換は {@link TableWriter} と同じ共通処理で行う。要求の文は、テナントの列を呼び出し側が渡した値で書き、条件に入れる。RLS
 * のないスキーマの表でも、別のテナントの行に当たらないようにするためである。期限を過ぎた行を全テナントで消す文だけは、テナントの条件を持たない。
 */
/* package */ final class IdempotencyKeyWriter {

  /** 一つの文に合う行の数の、あるべき値。 */
  private static final int ONE_ROW = 1;

  /** キーの行の文を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る共通処理。 */
  private final CommonColumns commonColumns;

  /* package */ IdempotencyKeyWriter(final DSLContext dsl, final CommonColumns commonColumns) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
  }

  /**
   * キーの行を入れる。期限切れの行があれば、新しいキーとして取り直す。
   *
   * <p>{@code INSERT ... ON CONFLICT (テナント, 利用者, キー) DO UPDATE ... WHERE expires_at <= now}
   * である。取り直すときは作成のカラムも書き直し、新しいキーを表すようにする。有効な行があれば何も書かずにその行をロックし、{@code false}
   * を返す。同じキーの行を別のトランザクションが入れていれば、その終わりを待つ。
   *
   * @return 行を入れたか、期限切れの行を取り直したら {@code true}
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合
   * @throws ConflictException 別のトランザクションの終わりを {@code lock_timeout} までに待てなかった場合（種類は {@code
   *     IDEMPOTENCY_IN_PROGRESS}）。原因は持たない
   */
  /* package */ boolean claim(
      final IdempotencyKeyTable keys,
      final IdempotencyKeyTable.ScopedKey scopedKey,
      final byte[] payloadHash,
      final Instant now,
      final Instant expiresAt) {
    TableWriter.requireTransaction(dsl);
    final int rows =
        ConflictTranslator.executeOrConflict(
            () ->
                dsl.insertInto(keys.table())
                    .set(keys.tenant(), scopedKey.tenant())
                    .set(keys.user(), scopedKey.user())
                    .set(keys.key(), scopedKey.key())
                    .set(keys.payloadHash(), payloadHash)
                    .set(keys.expiresAt(), expiresAt)
                    .set(commonColumns.forInsert(keys.table()))
                    .onConflict(keys.tenant(), keys.user(), keys.key())
                    .doUpdate()
                    .set(keys.payloadHash(), payloadHash)
                    .setNull(keys.result())
                    .set(keys.expiresAt(), expiresAt)
                    .set(commonColumns.forRecreate(keys.table()))
                    .set(keys.lockNo(), keys.lockNo().plus(1L))
                    .where(keys.expiresAt().le(now))
                    .execute(),
            target(keys.table(), scopedKey),
            ConflictException.Kind.IDEMPOTENCY_IN_PROGRESS);
    return rows == ONE_ROW;
  }

  /**
   * {@link #claim} で取ったキーの行に、業務の処理の Result を保存する。
   *
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合、更新件数が 1 でない場合
   */
  /* package */ void saveResult(
      final IdempotencyKeyTable keys,
      final IdempotencyKeyTable.ScopedKey scopedKey,
      final JSONB result) {
    TableWriter.requireTransaction(dsl);
    final Supplier<String> target = target(keys.table(), scopedKey);
    final int rows =
        ConflictTranslator.executeOrConflict(
            () ->
                dsl.update(keys.table())
                    .set(keys.result(), result)
                    .set(commonColumns.forUpdate(keys.table()))
                    .set(keys.lockNo(), keys.lockNo().plus(1L))
                    .where(keys.identifies(scopedKey))
                    .execute(),
            target);
    if (rows != ONE_ROW) {
      throw new IllegalStateException(
          "claimed idempotency key matched " + rows + " rows: " + target.get());
    }
  }

  /**
   * 1 つのテナントの、期限を過ぎた行を {@code limit} 行まで消す。
   *
   * <p>消す行の選び方は {@link #deleteWhere} と同じである。
   *
   * @return 消した行の数
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合
   */
  /* package */ static int deleteExpiredInTenant(
      final DSLContext dsl,
      final IdempotencyKeyTable keys,
      final UUID tenant,
      final Instant now,
      final int limit) {
    return deleteWhere(dsl, keys, keys.tenant().eq(tenant).and(keys.expiresAt().le(now)), limit);
  }

  /**
   * 全テナントの、期限を過ぎた行を {@code limit} 行まで消す。RLS の外の表だけに使う。
   *
   * <p>消す行の選び方は {@link #deleteWhere} と同じである。
   *
   * @return 消した行の数
   * @throws IllegalStateException トランザクションが開いていない、または読み取り専用の場合
   */
  /* package */ static int deleteExpiredInAllTenants(
      final DSLContext dsl, final IdempotencyKeyTable keys, final Instant now, final int limit) {
    return deleteWhere(dsl, keys, keys.expiresAt().le(now), limit);
  }

  /**
   * 条件に合う行を、{@code limit} 行まで消す。
   *
   * <p>消す行は {@code SELECT ... FOR UPDATE SKIP LOCKED} で選ぶ。{@link #claim}
   * が取り直している行はロックされているので飛ばし、取り直して commit した行は期限が延びているので選ばない。
   */
  private static int deleteWhere(
      final DSLContext dsl, final IdempotencyKeyTable keys, final Condition due, final int limit) {
    TableWriter.requireTransaction(dsl);
    return dsl.deleteFrom(keys.table())
        .where(
            keys.id()
                .in(
                    DSL.select(keys.id())
                        .from(keys.table())
                        .where(due)
                        .limit(limit)
                        .forUpdate()
                        .skipLocked()))
        .execute();
  }

  /** 例外のメッセージに入れる、キーの表の名前とキー。 */
  private static Supplier<String> target(
      final Table<?> table, final IdempotencyKeyTable.ScopedKey scopedKey) {
    return () -> "table=" + table.getName() + ", key=" + scopedKey.key();
  }
}
