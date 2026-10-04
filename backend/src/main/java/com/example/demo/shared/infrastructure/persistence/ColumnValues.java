package com.example.demo.shared.infrastructure.persistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;
import org.jspecify.annotations.Nullable;

/**
 * {@link TableWriter} の UPDATE で SET する業務の列の値だけを受け取る（ADR-054）。
 *
 * <p>{@code where} も {@code execute} も持たないため、Repository のラムダからは列の値しか書けない。列は更新するテーブルの型 {@code R}
 * に結び付いた {@link TableField} に限るため、別のテーブルの列と型の違う値はコンパイルで失敗する。
 *
 * <p>共通カラム（{@code lock_no} と、{@code created_*}、{@code updated_*}、{@code patched_*} の規約の 11 列）は
 * {@link TableWriter} が書くため、渡すと {@link IllegalArgumentException} を投げる。{@code updated_reason}
 * のような、規約にない業務の列は拒否しない。
 *
 * @param <R> 更新するテーブルの Record の型
 */
public final class ColumnValues<R extends Record> {

  /** {@link TableWriter} と共通処理だけが書く共通カラムの名前（docs/database/postgresql-common-columns.md）。 */
  private static final Set<String> COMMON_COLUMNS =
      Set.of(
          "created_at",
          "created_by",
          "created_pgm_cd",
          "created_tx_id",
          "updated_at",
          "updated_by",
          "updated_pgm_cd",
          "updated_tx_id",
          "lock_no",
          "patched_at",
          "patched_by",
          "patched_id");

  /** 更新するテーブル。メッセージに名前を入れるために持つ。 */
  private final Table<R> table;

  /** SET する列と値。SQL を決定的にするため、登録の順を保つ。 */
  // 一つの UPDATE を組み立てる間だけ一つのスレッドで使い、登録の順が要るため LinkedHashMap にする。
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private final Map<Field<?>, @Nullable Object> assignments = new LinkedHashMap<>();

  /* package */ ColumnValues(final Table<R> table) {
    this.table = table;
  }

  /**
   * 列に値を SET する。
   *
   * @throws IllegalArgumentException 共通カラムを渡した場合
   */
  public <T extends @Nullable Object> ColumnValues<R> set(
      final TableField<R, T> field, final T value) {
    return put(field, value);
  }

  /**
   * 列に式（{@code STOCK_COUNT.minus(5)} など）を SET する。
   *
   * @throws IllegalArgumentException 共通カラムを渡した場合
   */
  public <T extends @Nullable Object> ColumnValues<R> set(
      final TableField<R, T> field, final Field<T> value) {
    return put(field, value);
  }

  /** SET する列と値を、登録の順で返す。 */
  /* package */ Map<Field<?>, @Nullable Object> values() {
    return Collections.unmodifiableMap(assignments);
  }

  /** 一つも値を登録していなければ {@code true} を返す。 */
  /* package */ boolean isEmpty() {
    return assignments.isEmpty();
  }

  private ColumnValues<R> put(final TableField<R, ?> field, final @Nullable Object value) {
    if (COMMON_COLUMNS.contains(field.getName())) {
      throw new IllegalArgumentException(
          "common column is set by TableWriter: table="
              + table.getName()
              + ", column="
              + field.getName());
    }
    assignments.put(field, value);
    return this;
  }
}
