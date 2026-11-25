package com.example.demo.shared.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStepN;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;

/**
 * 集約の子の行を、ルートの行ロックの下で書く（ADR-054）。{@link TableWriter} だけが使う。
 *
 * <p>子の外部キーの条件は宣言（{@link ChildTable}）から作り、呼び出し側からは受け取らない。
 */
// jOOQ の型を使って子の行の文を組み立てるため、結合するクラスの数の上限を外す。
// 一つの保存の呼び出しの間だけ、一つのスレッドで使う Map は、順序を保つため LinkedHashMap にする。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.UseConcurrentHashMap"})
/* package */ final class ChildWriter {

  /** 子の主キーの条件で更新できる行数。 */
  private static final int ONE_ROW = 1;

  /** 子の行の文を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る共通処理。 */
  private final CommonColumns commonColumns;

  /* package */ ChildWriter(final DSLContext dsl, final CommonColumns commonColumns) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
  }

  /** 新しい集約の子の全行を、1 文の複数行の INSERT で書く。 */
  /* package */ <C extends Record> void insertAll(final long rootId, final ChildRows<C> rows) {
    insertChildren(rows.child(), rootId, List.copyOf(rows.rows().values()));
  }

  /**
   * 既存の集約の子を、保存済みの子との差分で書く。保存後にない子の削除、残る子の更新、新しい子の追加の順に実行する。
   *
   * @throws IllegalStateException 残る子の更新が 1 行でない場合
   */
  /* package */ <C extends Record> void replace(final long rootId, final ChildRows<C> rows) {
    final ChildTable<C> child = rows.child();
    final Map<List<Object>, ColumnValues<C>> desired = rows.rows();
    final Set<List<Object>> stored =
        new LinkedHashSet<>(
            dsl.select(child.naturalKey())
                .from(child.table())
                .where(child.parentKey().eq(rootId))
                .fetch(record -> List.of(record.intoArray())));
    final List<List<Object>> removed =
        stored.stream().filter(naturalKey -> !desired.containsKey(naturalKey)).toList();
    if (!removed.isEmpty()) {
      deleteChildren(child, rootId, removed);
    }
    final List<ColumnValues<C>> added = new ArrayList<>();
    desired.forEach((naturalKey, row) -> {
      if (stored.contains(naturalKey)) {
        updateChild(child, rootId, naturalKey, row);
      } else {
        added.add(row);
      }
    });
    insertChildren(child, rootId, added);
  }

  /** なくなった子を、自然キーの組で削除する。履歴の表がある子は、削除した行をそのまま履歴の表へ写す。 */
  private <C extends Record> void deleteChildren(
      final ChildTable<C> child, final long rootId, final List<List<Object>> removed) {
    final Table<C> table = child.table();
    final Condition removedKeys =
        DSL.or(removed.stream().map(naturalKey -> naturalKeyCondition(child, naturalKey)).toList());
    final Condition condition = child.parentKey().eq(rootId).and(removedKeys);
    final Supplier<String> target = () -> childTarget(table, rootId);
    final Result<C> deleted =
        ConflictTranslator.executeOrConflict(
            () -> dsl.deleteFrom(table).where(condition).returning(table.fields()).fetch(), target);
    child.archive().ifPresent(archive -> archiveRows(archive, deleted, target));
  }

  /**
   * 削除した行を、退避の日時、者、プログラム、トレースと一緒に、履歴の表へ 1 文の複数行の INSERT で写す。
   *
   * <p>写すのは {@code DELETE ... RETURNING} が返した行そのものなので、削除した行と写した行は一致する。返った行がなければ、INSERT を実行しない（値のない
   * INSERT は書けない）。削除と写しは、{@link TableWriter} が要求する同じトランザクションの中にあり、写しが失敗すると削除も戻る。
   */
  private void archiveRows(
      final ChildTable.Archive archive,
      final Result<? extends Record> deleted,
      final Supplier<String> target) {
    if (deleted.isEmpty()) {
      return;
    }
    final Map<Field<?>, Object> archived = commonColumns.forArchive(archive.table());
    final List<Field<?>> columns = new ArrayList<>(archive.columns());
    columns.addAll(archived.keySet());
    InsertValuesStepN<?> insert = dsl.insertInto(archive.table()).columns(columns);
    for (final Record row : deleted) {
      insert = insert.values(archivedValues(row, archived.values()));
    }
    final InsertValuesStepN<?> statement = insert;
    ConflictTranslator.executeOrConflict(statement::execute, target);
  }

  /** 履歴の表への INSERT の 1 行の値を、子の列の順（削除した行の値）の後に退避のカラムの値を続けて返す。 */
  private static List<@Nullable Object> archivedValues(
      final Record row, final Collection<Object> archived) {
    final List<@Nullable Object> values = new ArrayList<>();
    for (int i = 0; i < row.size(); i++) {
      values.add(row.get(i));
    }
    values.addAll(archived);
    return values;
  }

  /**
   * 残る子を 1 行更新し、その行の版を 1 進める。
   *
   * <p>ponytail: 上限：子の行の UPDATE は 1 行ずつ実行する。子が多い集約が出たら、バッチにして件数の配列で 1 件ずつを確かめる。
   */
  private <C extends Record> void updateChild(
      final ChildTable<C> child,
      final long rootId,
      final List<Object> naturalKey,
      final ColumnValues<C> row) {
    final Table<C> table = child.table();
    final Field<Long> lockNo =
        CommonColumns.requiredField(table, CommonColumns.LOCK_NO, Long.class);
    final Map<Field<?>, @Nullable Object> changes = new LinkedHashMap<>(row.values());
    child.naturalKey().forEach(changes::remove);
    final int updated =
        ConflictTranslator.executeOrConflict(
            () ->
                dsl.update(table)
                    .set(changes)
                    .set(commonColumns.forUpdate(table))
                    .set(lockNo, lockNo.plus(1))
                    .where(child.parentKey().eq(rootId).and(naturalKeyCondition(child, naturalKey)))
                    .execute(),
            () -> childTarget(table, rootId) + ", naturalKey=" + naturalKey);
    if (updated != ONE_ROW) {
      throw new IllegalStateException(
          "child natural key matched "
              + updated
              + " rows: "
              + childTarget(table, rootId)
              + ", naturalKey="
              + naturalKey);
    }
  }

  /** 新しい子を、1 文の複数行の INSERT で書く。 */
  private <C extends Record> void insertChildren(
      final ChildTable<C> child, final long rootId, final List<ColumnValues<C>> added) {
    if (added.isEmpty()) {
      return;
    }
    final Table<C> table = child.table();
    final List<Field<?>> businessFields = List.copyOf(added.getFirst().values().keySet());
    final Map<Field<?>, Object> common = commonColumns.forInsert(table);
    final List<Field<?>> fields = new ArrayList<>();
    fields.add(child.parentKey());
    fields.addAll(businessFields);
    fields.addAll(common.keySet());
    InsertValuesStepN<C> insert = dsl.insertInto(table).columns(fields);
    for (final ColumnValues<C> row : added) {
      insert = insert.values(rowValues(rootId, row, businessFields, common));
    }
    final InsertValuesStepN<C> statement = insert;
    ConflictTranslator.executeOrConflict(statement::execute, () -> childTarget(table, rootId));
  }

  /** 複数行の INSERT の 1 行の値を、列の順（親の外部キー、業務の列、共通カラム）で返す。 */
  private static <C extends Record> List<@Nullable Object> rowValues(
      final long rootId,
      final ColumnValues<C> row,
      final List<Field<?>> businessFields,
      final Map<Field<?>, Object> common) {
    final List<@Nullable Object> values = new ArrayList<>();
    values.add(rootId);
    businessFields.forEach(field -> values.add(row.values().get(field)));
    values.addAll(common.values());
    return values;
  }

  /** 自然キーの値の組に合う行の条件。 */
  private static <C extends Record> Condition naturalKeyCondition(
      final ChildTable<C> child, final List<Object> naturalKey) {
    final Map<Field<?>, Object> equalities = new LinkedHashMap<>();
    for (int i = 0; i < naturalKey.size(); i++) {
      equalities.put(child.naturalKey().get(i), naturalKey.get(i));
    }
    return DSL.condition(equalities);
  }

  /** 例外のメッセージに入れる、子のテーブル名と親の内部の主キー。 */
  private static String childTarget(final Table<?> table, final long rootId) {
    return "table=" + table.getName() + ", rootId=" + rootId;
  }
}
