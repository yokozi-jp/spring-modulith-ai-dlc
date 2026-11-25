package com.example.demo.shared.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

/**
 * 集約の子のテーブルの宣言。Repository がクラスの定数として一度だけ書く（ADR-054）。
 *
 * <p>自然キーは、子のテーブルの一意制約で裏づける。裏づけがないと、保存済みの子を自然キーで引いた更新が 2 行に合い、{@link IllegalStateException} になる。
 *
 * <p>親の中で行を同定する自然キー（{@code line_no} など）と、親の内部の主キーを持つ外部キーの列を持つ。{@link TableWriter}
 * は、子の書き込みのたびに外部キーの列の条件を自分で付けるため、親の条件を書き忘れた子の書き込みは書けない。
 *
 * @param <C> 子のテーブルの Record の型
 */
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class ChildTable<C extends Record> {

  /** 自然キーに使える型。{@code BigDecimal} の桁数や {@code Instant} の精度の違いで、保存済みの値と一致しなくなる型は使えない。 */
  private static final Set<Class<?>> COMPARABLE_TYPES =
      Set.of(Integer.class, Long.class, Short.class, String.class, UUID.class);

  /** 子のテーブル。 */
  private final Table<C> table;

  /** 親の内部の主キーを持つ外部キーの列。 */
  private final TableField<C, Long> parentKey;

  /** 親の中で行を同定する列。一つ以上。 */
  private final List<TableField<C, ?>> naturalKey;

  /** 削除する前の行を写す履歴の表。 */
  private final Archive archive;

  private ChildTable(
      final Table<C> table,
      final TableField<C, Long> parentKey,
      final List<TableField<C, ?>> naturalKey,
      final Archive archive) {
    this.table = table;
    this.parentKey = parentKey;
    this.naturalKey = List.copyOf(naturalKey);
    this.archive = archive;
  }

  /**
   * 子のテーブルを宣言する。子の行は、削除する前に履歴の表へ写す（ADR-129）。
   *
   * <p>履歴の表は、子のテーブルのすべての列（共通カラムを含む）を同じ名前と Java の型で持ち、退避のカラム（{@code archived_at}、{@code
   * archived_by}、{@code archived_pgm_cd}、{@code
   * archived_tx_id}）を持つ。子の主キーと自然キーに一意制約を付けず、同じ子を作り直して消しても書ける形にする。
   *
   * <p>ponytail: 上限：列の長さと NULL の可否は、この宣言では比べない。{@code AggregateDeclarationInspectionTest} が DB
   * のカタログで比べる。
   *
   * @param table 子のテーブル
   * @param parentKey 親の内部の主キーを持つ外部キーの列
   * @param archive 削除する前の行を写す履歴の表
   * @param firstKey 親の中で行を同定する列
   * @param moreKeys 自然キーが複数の列のときの残りの列
   * @throws IllegalArgumentException テーブルに共通カラムがない場合、履歴の表に子の列か退避のカラムがない、または型が異なる場合
   */
  @SafeVarargs
  public static <C extends Record> ChildTable<C> of(
      final Table<C> table,
      final TableField<C, Long> parentKey,
      final Table<?> archive,
      final TableField<C, ?> firstKey,
      final TableField<C, ?>... moreKeys) {
    CommonColumns.requireAuditColumns(table);
    final List<TableField<C, ?>> naturalKey = new ArrayList<>();
    naturalKey.add(firstKey);
    naturalKey.addAll(List.of(moreKeys));
    naturalKey.forEach(ChildTable::requireComparableType);
    final List<Field<?>> columns =
        table
            .fieldStream()
            .<Field<?>>map(
                field -> CommonColumns.requiredField(archive, field.getName(), field.getType()))
            .toList();
    CommonColumns.requireArchiveColumns(archive);
    return new ChildTable<>(table, parentKey, naturalKey, new Archive(archive, columns));
  }

  /**
   * 履歴の表と、子のテーブルの列と同じ順に並べた、履歴の表の列。
   *
   * @param table 履歴の表
   * @param columns 子のテーブルの列と同じ順の、履歴の表の列
   */
  /* package */ record Archive(Table<?> table, List<Field<?>> columns) {}

  /**
   * 保存後にあるべき、この子の全行を作る。{@link TableWriter} の入口に渡す。
   *
   * @param items 子の行にする業務の値。空なら、その子の行をすべて消す
   * @param columns 業務の値から子の列の値を作る。自然キーの列も書く。親の外部キーと共通カラムは書けない
   * @throws IllegalArgumentException 自然キーが {@code null} か重複する場合、行ごとに書く列が違う場合、書けない列を渡した場合
   */
  public <T> ChildRows<C> rows(
      final Collection<? extends T> items, final BiConsumer<? super T, ColumnValues<C>> columns) {
    return ChildRows.of(this, items, columns);
  }

  /**
   * 子の行の自然キーの値の組を返す。
   *
   * @throws IllegalArgumentException 自然キーの列を書いていない、または式を書いた場合
   */
  /* package */ List<Object> naturalKeyOf(final ColumnValues<C> row) {
    return naturalKey.stream().map(field -> naturalKeyValue(row, field)).toList();
  }

  private Object naturalKeyValue(final ColumnValues<C> row, final TableField<C, ?> field) {
    final Object value = row.values().get(field);
    if (value == null || value instanceof Field<?>) {
      throw new IllegalArgumentException(
          "natural key must be a non-null value: table="
              + table.getName()
              + ", column="
              + field.getName());
    }
    return value;
  }

  /** 自然キーの値は、保存済みの値と {@code equals} で比べるため、比べられる型に限る。 */
  private static void requireComparableType(final TableField<?, ?> field) {
    if (!COMPARABLE_TYPES.contains(field.getType())) {
      throw new IllegalArgumentException(
          "natural key column must be Integer, Long, Short, String or UUID: column="
              + field.getName()
              + ", type="
              + field.getType().getSimpleName());
    }
  }

  /* package */ Archive archive() {
    return archive;
  }

  /* package */ Table<C> table() {
    return table;
  }

  /* package */ TableField<C, Long> parentKey() {
    return parentKey;
  }

  /* package */ List<TableField<C, ?>> naturalKey() {
    return naturalKey;
  }
}
