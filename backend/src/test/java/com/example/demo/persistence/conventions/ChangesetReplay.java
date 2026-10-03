package com.example.demo.persistence.conventions;

import static java.util.Map.entry;

import com.example.demo.persistence.conventions.Changeset.Change;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * changesetをファイル名の順に適用し、各テーブルの最終形を組み立てる。
 *
 * <p>Change Typeで書いたテーブルだけを扱い、{@code rollback}は適用しない。
 *
 * <p>ponytail: {@code
 * sql}変更で作ったテーブルは組み立てない。任意のSQLは許可リストに理由を付けて載せ、レビューで確認するためである。そうしたテーブルが現れたら、DDLを解析するか、マイグレーション後のカタログと照合する。
 */
// 検査の1回の実行の中だけで使う、単一スレッドの作業用の状態である。
@SuppressWarnings("PMD.UseConcurrentHashMap")
final class ChangesetReplay {

  /**
   * 型の別名と、比較に使う正規の名前。
   *
   * <p>ponytail: この表にない別名は、小文字にしただけで比較する。表の網羅性が検査の上限であり、超えるならマイグレーション後のカタログの型と照合する。
   */
  private static final Map<String, String> TYPE_ALIASES =
      Map.ofEntries(
          entry("timestamp with time zone", "timestamptz"),
          entry("timestamp without time zone", "timestamp"),
          entry("datetime", "timestamp"),
          entry("character varying", "varchar"),
          entry("nvarchar", "varchar"),
          entry("character", "char"),
          entry("nchar", "char"),
          entry("bpchar", "char"),
          entry("clob", "text"),
          entry("int8", "bigint"),
          entry("int", "integer"),
          entry("int4", "integer"),
          entry("mediumint", "integer"),
          entry("int2", "smallint"),
          entry("tinyint", "smallint"),
          entry("bool", "boolean"),
          entry("float4", "real"),
          entry("float8", "double precision"),
          entry("serial4", "serial"),
          entry("serial8", "bigserial"),
          entry("serial2", "smallserial"),
          entry("decimal", "numeric"),
          entry("number", "numeric"));

  /** スキーマで修飾したテーブル名から、組み立て中のテーブルへの対応。 */
  private final Map<String, TableShape> tables = new LinkedHashMap<>();

  /** changesetのidから、そのchangesetの{@code createTable}で作ったテーブルの修飾名への対応。 */
  private final Map<String, Set<String>> tablesCreatedIn = new HashMap<>();

  private ChangesetReplay() {
    // replay()だけから使う。
  }

  /**
   * changesetを順に適用した結果を返す。
   *
   * @param changesets ファイル名の順に並べたchangeset
   * @return 各テーブルの最終形と、changesetごとに作ったテーブル
   */
  /* package */ static Result replay(final List<Changeset> changesets) {
    final ChangesetReplay replay = new ChangesetReplay();
    for (final Changeset changeset : changesets) {
      for (final Change change : changeset.changes()) {
        replay.apply(changeset, change);
      }
    }
    return new Result(List.copyOf(replay.tables.values()), Map.copyOf(replay.tablesCreatedIn));
  }

  /**
   * 型を小文字にし、空白をそろえ、別名を正規の名前に置き換える。
   *
   * @param type changesetに書いた型
   * @return 比較に使う型（{@code varchar(64)}、{@code timestamptz}など）
   */
  /* package */ static String normalizeType(final String type) {
    final String collapsed =
        type.strip()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .replaceAll("\\s*([(),])\\s*", "$1");
    final int paren = collapsed.indexOf('(');
    final String base = paren < 0 ? collapsed : collapsed.substring(0, paren);
    final String arguments = paren < 0 ? "" : collapsed.substring(paren);
    return TYPE_ALIASES.getOrDefault(base, base) + arguments;
  }

  /**
   * スキーマとテーブル名を{@code schema.table}の形に修飾する。
   *
   * @param schema スキーマ名
   * @param table テーブル名
   * @return 修飾したテーブル名
   */
  /* package */ static String qualify(final String schema, final String table) {
    return schema + "." + table;
  }

  private void apply(final Changeset changeset, final Change change) {
    final String schema = change.schema();
    final String table = qualify(schema, change.table());
    switch (change.type()) {
      case "createTable" -> {
        final TableShape created =
            new TableShape(
                schema,
                change.table(),
                changeset.hasComment(),
                new LinkedHashMap<>(columnsOf(change)));
        tables.put(table, created);
        tablesCreatedIn.computeIfAbsent(changeset.changesetId(), id -> new HashSet<>()).add(table);
      }
      case "renameTable" -> renameTable(schema, change);
      case "dropTable" -> tables.remove(table);
      default -> {
        final TableShape target = tables.get(table);
        if (target != null) {
          applyColumnChange(target.columns(), change);
        }
      }
    }
  }

  private void renameTable(final String schema, final Change change) {
    final TableShape renamed = tables.remove(qualify(schema, change.text("oldTableName")));
    if (renamed != null) {
      final String newName = change.text("newTableName");
      tables.put(
          qualify(schema, newName),
          new TableShape(schema, newName, renamed.createdWithComment(), renamed.columns()));
    }
  }

  private static void applyColumnChange(
      final Map<String, ColumnShape> columns, final Change change) {
    final String column = change.text("columnName");
    switch (change.type()) {
      case "addColumn" -> columns.putAll(columnsOf(change));
      case "dropColumn" -> {
        columns.remove(column);
        change.columns().forEach(dropped -> columns.remove(YamlNodes.text(dropped, "name")));
      }
      case "renameColumn" -> {
        final ColumnShape renamed = columns.remove(change.text("oldColumnName"));
        if (renamed != null) {
          columns.put(change.text("newColumnName"), renamed);
        }
      }
      case "modifyDataType" ->
          update(columns, column, shape -> shape.withType(change.text("newDataType")));
      case "addPrimaryKey" ->
          Arrays.stream(change.text("columnNames").split(","))
              .forEach(name -> update(columns, name.strip(), shape -> shape.withNullable(false)));
      default -> applyConstraintChange(columns, column, change.type());
    }
  }

  private static void applyConstraintChange(
      final Map<String, ColumnShape> columns, final String column, final String type) {
    switch (type) {
      case "addNotNullConstraint" -> update(columns, column, shape -> shape.withNullable(false));
      case "dropNotNullConstraint" -> update(columns, column, shape -> shape.withNullable(true));
      case "addDefaultValue" -> update(columns, column, shape -> shape.withDefault(true));
      case "dropDefaultValue" -> update(columns, column, shape -> shape.withDefault(false));
      default -> {
        // テーブルの形を変えないChange Typeは無視する。
      }
    }
  }

  private static void update(
      final Map<String, ColumnShape> columns,
      final String column,
      final UnaryOperator<ColumnShape> change) {
    columns.computeIfPresent(column, (name, shape) -> change.apply(shape));
  }

  private static Map<String, ColumnShape> columnsOf(final Change change) {
    final Map<String, ColumnShape> columns = new LinkedHashMap<>();
    for (final Map<String, Object> column : change.columns()) {
      columns.put(YamlNodes.text(column, "name"), ColumnShape.fromColumn(column));
    }
    return columns;
  }

  /**
   * changesetを適用した結果。
   *
   * @param tables 各テーブルの最終形
   * @param tablesCreatedIn changesetのidから、そのchangesetで作ったテーブルの修飾名への対応
   */
  /* package */ record Result(
      Collection<TableShape> tables, Map<String, Set<String>> tablesCreatedIn) {

    /** 指定したchangesetの{@code createTable}で、指定したテーブルを作ったか。 */
    /* package */ boolean createdIn(final String changesetId, final String qualifiedTable) {
      return tablesCreatedIn.getOrDefault(changesetId, Set.of()).contains(qualifiedTable);
    }

    /** 最終形に、指定したスキーマとテーブル名のテーブルがあるか。 */
    /* package */ boolean hasTable(final String schema, final String table) {
      return tables.stream()
          .anyMatch(shape -> shape.schema().equals(schema) && shape.name().equals(table));
    }
  }

  /**
   * テーブルの最終形。
   *
   * @param schema スキーマ名
   * @param name テーブル名
   * @param createdWithComment テーブルを作ったchangesetに{@code comment}があるか
   * @param columns カラム名から、カラムの最終形への対応（定義した順）
   */
  /* package */ record TableShape(
      String schema, String name, boolean createdWithComment, Map<String, ColumnShape> columns) {

    /** {@code schema.table}の形の名前。 */
    /* package */ String qualifiedName() {
      return qualify(schema, name);
    }
  }

  /**
   * カラムの最終形。
   *
   * @param type 正規化した型
   * @param nullable NULLを許すか
   * @param hasDefault DBの既定値を持つか
   */
  /* package */ record ColumnShape(String type, boolean nullable, boolean hasDefault) {

    /**
     * changesetの{@code column}要素から作る。
     *
     * <p>{@code nullable: false}か{@code primaryKey: true}ならNOT NULL、{@code
     * defaultValue}で始まる属性があれば既定値ありとする。
     *
     * @param column {@code column}要素の中身
     * @return カラムの形
     */
    private static ColumnShape fromColumn(final Map<String, Object> column) {
      final Map<String, Object> constraints = YamlNodes.map(column.get("constraints"));
      final boolean notNull =
          YamlNodes.isFalse(constraints.get("nullable"))
              || YamlNodes.isTrue(constraints.get("primaryKey"));
      final boolean hasDefault =
          column.keySet().stream().anyMatch(key -> key.startsWith("defaultValue"));
      return new ColumnShape(normalizeType(YamlNodes.text(column, "type")), !notNull, hasDefault);
    }

    private ColumnShape withType(final String newType) {
      return new ColumnShape(normalizeType(newType), nullable, hasDefault);
    }

    private ColumnShape withNullable(final boolean newNullable) {
      return new ColumnShape(type, newNullable, hasDefault);
    }

    private ColumnShape withDefault(final boolean newHasDefault) {
      return new ColumnShape(type, nullable, newHasDefault);
    }
  }
}
