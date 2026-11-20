package com.example.demo.shared.infrastructure.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStepN;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;
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

  /** 版の最小値ではなく、一つの文で変える行数。 */
  private static final int ONE_ROW = 1;

  /** 楽観的ロックの版のカラム名。 */
  private static final String LOCK_NO = "lock_no";

  /** 子の行の文を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る共通処理。 */
  private final CommonColumns commonColumns;

  /* package */ ChildWriter(final DSLContext dsl, final CommonColumns commonColumns) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
  }

  /**
   * 子の行を書く。{@code replace} なら、保存済みの子との差分で書く。
   *
   * @throws IllegalArgumentException 子の行の列が揃っていない場合、自然キーが null か重複する場合
   */
  /* package */ <R extends Record, C extends Record> void saveChild(
      final ChildTable<R, C> child,
      final long rootId,
      final ChildRows<R> rows,
      final boolean replace) {
    final Table<C> table = child.table();
    final Map<List<Object>, ColumnValues<C>> desiredByKey = desiredByKey(child, rows);
    final Set<List<Object>> stored =
        replace
            ? new LinkedHashSet<>(
                dsl.select(child.naturalKey())
                    .from(table)
                    .where(child.parentKey().eq(rootId))
                    .fetch(record -> List.of(record.intoArray())))
            : Set.of();
    final List<List<Object>> removed =
        stored.stream().filter(naturalKey -> !desiredByKey.containsKey(naturalKey)).toList();
    if (!removed.isEmpty()) {
      deleteChildren(child, rootId, removed);
    }
    final List<ColumnValues<C>> added = new ArrayList<>();
    desiredByKey.forEach((naturalKey, row) -> {
      if (stored.contains(naturalKey)) {
        updateChild(child, rootId, naturalKey, row);
      } else {
        added.add(row);
      }
    });
    insertChildren(child, rootId, added);
  }

  /** なくなった子を、自然キーの組で削除する。 */
  private <R extends Record, C extends Record> void deleteChildren(
      final ChildTable<R, C> child, final long rootId, final List<List<Object>> removed) {
    final Table<C> table = child.table();
    final Condition removedKeys =
        DSL.or(removed.stream().map(naturalKey -> naturalKeyCondition(child, naturalKey)).toList());
    ConflictTranslator.executeOrConflict(
        () -> dsl.deleteFrom(table).where(child.parentKey().eq(rootId).and(removedKeys)).execute(),
        () -> childTarget(table, rootId));
  }

  /** 残る子を 1 行更新し、その行の版を 1 進める。 */
  private <R extends Record, C extends Record> void updateChild(
      final ChildTable<R, C> child,
      final long rootId,
      final List<Object> naturalKey,
      final ColumnValues<C> row) {
    final Table<C> table = child.table();
    final Field<Long> lockNo = CommonColumns.requiredField(table, LOCK_NO, Long.class);
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
  private <R extends Record, C extends Record> void insertChildren(
      final ChildTable<R, C> child, final long rootId, final List<ColumnValues<C>> added) {
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
    common.keySet().forEach(field -> values.add(common.get(field)));
    return values;
  }

  /**
   * 子の行を自然キーで引く。SQL を実行する前に、行の不備を {@link IllegalArgumentException} にするために使う。
   *
   * @throws IllegalArgumentException 子の行を渡していない場合、自然キーが null か重複する場合、行ごとに書く列が違う場合
   */
  /* package */ static <R extends Record, C extends Record>
      Map<List<Object>, ColumnValues<C>> desiredByKey(
          final ChildTable<R, C> child, final ChildRows<R> rows) {
    final Table<C> table = child.table();
    final Map<List<Object>, ColumnValues<C>> byKey = new LinkedHashMap<>();
    List<Field<?>> columns = List.of();
    for (final ColumnValues<C> row : rows.rowsOf(child)) {
      final List<Field<?>> rowColumns = List.copyOf(row.values().keySet());
      if (byKey.isEmpty()) {
        columns = rowColumns;
      } else if (!rowColumns.equals(columns)) {
        throw new IllegalArgumentException(
            "child rows must set the same columns: table=" + table.getName());
      }
      final List<Object> naturalKey = naturalKeyOf(child, row);
      if (byKey.put(naturalKey, row) != null) {
        throw new IllegalArgumentException(
            "natural key is duplicated: table=" + table.getName() + ", key=" + naturalKey);
      }
    }
    return byKey;
  }

  /** 子の行の自然キーの値の組を返す。 */
  private static <R extends Record, C extends Record> List<Object> naturalKeyOf(
      final ChildTable<R, C> child, final ColumnValues<C> row) {
    return child.naturalKey().stream()
        .map(field -> {
          final Object value = row.values().get(field);
          if (value == null || value instanceof Field<?>) {
            throw new IllegalArgumentException(
                "natural key must be a non-null value: table="
                    + child.table().getName()
                    + ", column="
                    + field.getName());
          }
          return value;
        })
        .toList();
  }

  /** 自然キーの値の組に合う行の条件。 */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <R extends Record, C extends Record> Condition naturalKeyCondition(
      final ChildTable<R, C> child, final List<Object> naturalKey) {
    final List<TableField<C, ?>> fields = child.naturalKey();
    final List<Condition> conditions = new ArrayList<>();
    for (int i = 0; i < fields.size(); i++) {
      conditions.add(((Field) fields.get(i)).eq(naturalKey.get(i)));
    }
    return DSL.and(conditions);
  }

  /** 例外のメッセージに入れる、子のテーブル名と親の内部の主キー。 */
  private static String childTarget(final Table<?> table, final long rootId) {
    return "table=" + table.getName() + ", rootId=" + rootId;
  }
}
