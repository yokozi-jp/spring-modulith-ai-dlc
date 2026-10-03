package com.example.demo.shared.infrastructure.persistence;

import java.util.NoSuchElementException;
import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.stereotype.Component;

/**
 * 楽観的ロックの UPDATE の更新件数を判定する（docs/database/postgresql-concurrency-control.md、ADR-052）。
 *
 * <p>Repository は主キーと {@code lock_no} を条件にした UPDATE の更新件数を {@link #requireUpdated} に渡す。
 *
 * <p>0 件のときだけ主キーで行の有無を確かめ、行がないか、他の人が更新したかを区別する。
 *
 * <p>競合の例外は呼び出し側が関数で渡すため、このクラスは Domain と {@code error} の型を知らない。
 */
@Component
public class OptimisticLock {

  /** 主キーの条件で更新できる行数。 */
  private static final int ONE_ROW = 1;

  /** 0 件のときに行の有無を確かめる jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 行の有無を確かめる jOOQ のコンテキストを受け取る。 */
  public OptimisticLock(final DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * 主キーと {@code lock_no} を条件にした UPDATE が、ちょうど 1 行を更新したことを確かめる。
   *
   * @param updated UPDATE の更新件数
   * @param table 更新したテーブル
   * @param byPrimaryKey 主キーで 1 行を特定する条件。{@code lock_no} の条件は含めない
   * @param conflict 競合の例外をメッセージから作る関数（例：{@code OrderConflictException::new}）
   * @throws NoSuchElementException 更新件数が 0 で、行がない場合
   * @throws RuntimeException 更新件数が 0 で、行がある場合。{@code conflict} が作った例外
   * @throws IllegalStateException 更新件数が 2 以上の場合。主キーの条件が 1 行を特定していない
   */
  public void requireUpdated(
      final int updated,
      final Table<?> table,
      final Condition byPrimaryKey,
      final Function<String, ? extends RuntimeException> conflict) {
    if (updated == ONE_ROW) {
      return;
    }
    final String target = "table=" + table.getName() + ", where=" + byPrimaryKey;
    if (updated != 0) {
      throw new IllegalStateException(
          "primary key condition updated " + updated + " rows: " + target);
    }
    if (!dsl.fetchExists(table, byPrimaryKey)) {
      throw new NoSuchElementException("row not found: " + target);
    }
    throw conflict.apply("row was updated by another request: " + target);
  }
}
