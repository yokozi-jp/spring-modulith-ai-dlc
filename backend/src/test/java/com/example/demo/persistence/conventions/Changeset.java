package com.example.demo.persistence.conventions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * changesetのファイル1つから読んだ{@code changeSet}要素。
 *
 * <p>SnakeYAMLが返す{@code Map}と{@code List}をそのまま持ち、検査が使う属性だけを型付きで取り出す。
 *
 * @param fileName changesetのファイル名
 * @param node {@code changeSet}要素の中身
 */
record Changeset(String fileName, Map<String, Object> node) {

  /** changesetのid。 */
  /* package */ String changesetId() {
    return YamlNodes.text(node, "id");
  }

  /** changesetのauthor。 */
  /* package */ String author() {
    return YamlNodes.text(node, "author");
  }

  /** changesetの{@code comment}が空白以外の文字を含むか。 */
  /* package */ boolean hasComment() {
    return !YamlNodes.text(node, "comment").isBlank();
  }

  /** {@code runInTransaction: false}を指定していないか。 */
  /* package */ boolean runsInTransaction() {
    return !YamlNodes.isFalse(node.get("runInTransaction"));
  }

  /** {@code changes}の各変更。 */
  /* package */ List<Change> changes() {
    return YamlNodes.list(node.get("changes")).stream().flatMap(Change::from).toList();
  }

  /** {@code rollback}の各変更。文字列のrollbackは{@code sql}変更として返す。 */
  /* package */ List<Change> rollbackChanges() {
    final Object rollback = node.get("rollback");
    if (rollback instanceof List<?> items) {
      return items.stream().flatMap(Change::from).toList();
    }
    return Change.from(rollback).toList();
  }

  /** {@code changes}と{@code rollback}の全変更。 */
  /* package */ Stream<Change> allChanges() {
    return Stream.concat(changes().stream(), rollbackChanges().stream());
  }

  /**
   * Change Typeの1件。
   *
   * @param type Change Typeの名前（{@code createTable}など）
   * @param attributes Change Typeの属性
   */
  /* package */ record Change(String type, Map<String, Object> attributes) {

    /** {@code sql}変更のChange Typeと属性の名前。 */
    private static final String SQL = "sql";

    /** Change Typeの属性の文字列値。なければ空文字。 */
    /* package */ String text(final String key) {
      return YamlNodes.text(attributes, key);
    }

    /** {@code schemaName}の値。なければ空文字。 */
    /* package */ String schema() {
      return text("schemaName");
    }

    /** {@code tableName}の値。なければ空文字。 */
    /* package */ String table() {
      return text("tableName");
    }

    /** {@code columns}の各{@code column}の中身。 */
    /* package */ List<Map<String, Object>> columns() {
      return YamlNodes.list(attributes.get("columns")).stream()
          .map(item -> YamlNodes.map(YamlNodes.map(item).get("column")))
          .toList();
    }

    /**
     * 任意のSQLを持つ変更（{@code sql}、{@code sqlFile}、{@code createProcedure}）なら、そのSQLを表す文字列を返す。
     *
     * @return {@code sql}はSQLの本文、{@code sqlFile}と{@code createProcedure}は種類とパスまたは本文
     */
    /* package */ Optional<String> rawSql() {
      return switch (type) {
        case SQL -> Optional.of(text(SQL).strip());
        case "sqlFile" -> Optional.of("sqlFile " + text("path").strip());
        case "createProcedure" ->
            Optional.of(
                "createProcedure "
                    + (text("path").isBlank() ? text("procedureText") : text("path")).strip());
        default -> Optional.empty();
      };
    }

    /**
     * YAMLの1要素をChange Typeに変換する。文字列は{@code sql}変更とみなす。
     *
     * @param item {@code changes}または{@code rollback}の1要素
     * @return 変換した変更。空の要素なら空のストリーム
     */
    private static Stream<Change> from(final Object item) {
      if (item instanceof String sql) {
        return sql.isBlank() ? Stream.empty() : Stream.of(new Change(SQL, Map.of(SQL, sql)));
      }
      return YamlNodes.map(item).entrySet().stream()
          .map(
              entry ->
                  new Change(
                      entry.getKey(),
                      entry.getValue() instanceof String value
                          ? Map.of(SQL, value)
                          : YamlNodes.map(entry.getValue())));
    }
  }
}
