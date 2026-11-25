package com.example.demo.shared.persistence;

import com.example.demo.shared.concurrency.ConflictException;
import java.time.Instant;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Table;

/**
 * モジュールのスキーマの {@code Idempotency-Key} の行を書く（ADR-126）。{@link JooqIdempotency} だけが使う。
 *
 * <p>共通カラムと SQLSTATE の変換は {@link TableWriter} と同じ共通処理で行う。どの文も、テナントの列を呼び出し側が渡した値で書き、条件に入れる。RLS
 * のないスキーマの表でも、別のテナントの行に当たらないようにするためである。
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

  /** 例外のメッセージに入れる、キーの表の名前とキー。 */
  private static Supplier<String> target(
      final Table<?> table, final IdempotencyKeyTable.ScopedKey scopedKey) {
    return () -> "table=" + table.getName() + ", key=" + scopedKey.key();
  }
}
