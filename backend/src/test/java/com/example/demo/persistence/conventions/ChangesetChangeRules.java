package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.Changeset.Change;
import com.example.demo.persistence.conventions.ChangesetReplay.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 業務テーブルへの変更について、識別子、インデックスとシーケンスの名前、一意性の規則（N2、N4、N5、N7、N8、K3）を判定する。
 *
 * <p>{@code changes}だけを判定し、{@code rollback}は判定しない。{@code modulith}スキーマへの変更は判定しない。
 */
final class ChangesetChangeRules {

  /** 命名規約の文書。 */
  private static final String NAMING_DOC = "docs/database/postgresql-naming.md";

  /** 引用符の要らない識別子。 */
  private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

  /** PostgreSQLが切り詰めずに扱える識別子のバイト数。 */
  private static final int MAX_IDENTIFIER_BYTES = 63;

  /** スキーマ直下のオブジェクトの名前を持つ属性。 */
  private static final List<String> OBJECT_NAME_KEYS =
      List.of("indexName", "constraintName", "sequenceName", "newSequenceName", "viewName");

  /** テーブルを修飾名で持つ属性。 */
  private static final List<String> TABLE_NAME_KEYS = List.of("tableName", "newTableName");

  /** カラム名を持つ属性。 */
  private static final List<String> COLUMN_NAME_KEYS = List.of("columnName", "newColumnName");

  /** {@code column}要素の{@code constraints}にある制約名の属性。 */
  private static final List<String> CONSTRAINT_NAME_KEYS =
      List.of("primaryKeyName", "uniqueConstraintName", "foreignKeyName");

  /** ビュー名の種別の接頭辞（ビュー、マテリアライズドビュー、postgres_fdwで公開するビュー）。 */
  private static final Pattern VIEW_PREFIX = Pattern.compile("^(v|mv|fdw)_[a-z0-9_]+$");

  /** 明示的に作るシーケンスの名前。グループはテーブル名。 */
  private static final Pattern SEQUENCE_NAME = Pattern.compile("^seq_([a-z0-9_]+)_\\d+$");

  private ChangesetChangeRules() {
    // 静的メソッドだけを持つ。
  }

  /**
   * 業務テーブルへの変更を判定する。
   *
   * @param changesets ファイル名の順に並べたchangeset
   * @param replay changesetを適用した結果
   * @return 違反
   */
  /* package */ static List<ConventionViolation> check(
      final List<Changeset> changesets, final Result replay) {
    final List<ConventionViolation> violations = new ArrayList<>();
    for (final Changeset changeset : changesets) {
      for (final Change change : changeset.changes()) {
        if (ChangesetLint.isBusinessSchema(change.schema())) {
          identifiers(change, violations);
          objectNames(change, replay, violations);
        }
      }
    }
    return violations;
  }

  /** N2、N4：変更に現れるすべての識別子。 */
  private static void identifiers(final Change change, final List<ConventionViolation> out) {
    final String schema = change.schema();
    final String table = change.table();
    for (final String key : TABLE_NAME_KEYS) {
      checkIdentifier(schema, change.text(key), out);
    }
    for (final String key : OBJECT_NAME_KEYS) {
      checkIdentifier(schema, change.text(key), out);
    }
    for (final String key : COLUMN_NAME_KEYS) {
      checkIdentifier(schema + "." + table, change.text(key), out);
    }
    for (final Map<String, Object> column : change.columns()) {
      if (!YamlNodes.isTrue(column.get("computed"))) {
        checkIdentifier(schema + "." + table, YamlNodes.text(column, "name"), out);
      }
      final Map<String, Object> constraints = YamlNodes.map(column.get("constraints"));
      for (final String key : CONSTRAINT_NAME_KEYS) {
        checkIdentifier(schema, YamlNodes.text(constraints, key), out);
      }
    }
  }

  private static void checkIdentifier(
      final String scope, final String name, final List<ConventionViolation> out) {
    if (name.isEmpty()) {
      return;
    }
    final String target = scope + "." + name;
    if (!PLAIN_IDENTIFIER.matcher(name).matches()) {
      out.add(
          new ConventionViolation(
              "N2", target, "識別子は引用符の要らない小文字、数字、アンダースコアで書く（" + NAMING_DOC + "）。"));
    }
    if (name.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
      out.add(
          new ConventionViolation("N4", target, "識別子を63バイト以内にする。超えると切り詰められる（" + NAMING_DOC + "）。"));
    }
  }

  /** N5（ビュー）、N7、N8、K3：オブジェクトの名前と一意性。 */
  private static void objectNames(
      final Change change, final Result replay, final List<ConventionViolation> out) {
    final String schema = change.schema();
    final String table = change.table();
    switch (change.type()) {
      case "createTable", "addColumn" -> columnConstraints(change, out);
      case "addPrimaryKey" -> primaryKeyName(schema, table, change.text("constraintName"), out);
      case "createIndex" -> indexName(change, out);
      case "createSequence" -> sequenceName(schema, change.text("sequenceName"), replay, out);
      case "createView" -> {
        if (!VIEW_PREFIX.matcher(change.text("viewName")).matches()) {
          out.add(
              new ConventionViolation(
                  "N5",
                  schema + "." + change.text("viewName"),
                  "ビュー名に種別の接頭辞（v_、mv_、fdw_）を付ける（" + NAMING_DOC + "）。"));
        }
      }
      case "addUniqueConstraint" ->
          out.add(unique(schema, table, change.text("columnNames").replaceAll("\\s", "")));
      default -> {
        // 名前の規則がないChange Typeは判定しない。
      }
    }
  }

  private static void columnConstraints(final Change change, final List<ConventionViolation> out) {
    final String schema = change.schema();
    final String table = change.table();
    for (final Map<String, Object> column : change.columns()) {
      final Map<String, Object> constraints = YamlNodes.map(column.get("constraints"));
      if (YamlNodes.isTrue(constraints.get("primaryKey"))) {
        primaryKeyName(schema, table, YamlNodes.text(constraints, "primaryKeyName"), out);
      }
      if (YamlNodes.isTrue(constraints.get("unique"))) {
        out.add(unique(schema, table, YamlNodes.text(column, "name")));
      }
    }
  }

  private static void primaryKeyName(
      final String schema,
      final String table,
      final String name,
      final List<ConventionViolation> out) {
    if (!("pk_" + table).equals(name)) {
      out.add(
          new ConventionViolation(
              "N7",
              ChangesetReplay.qualify(schema, name.isEmpty() ? table : name),
              "主キーの名前をpk_" + table + "にして明示する（" + NAMING_DOC + "）。"));
    }
  }

  private static void indexName(final Change change, final List<ConventionViolation> out) {
    final String table = Pattern.quote(change.table());
    final String name = change.text("indexName");
    final boolean unique = YamlNodes.isTrue(change.attributes().get("unique"));
    final String expected =
        unique ? "^uk_(\\d+_)?" + table + "$" : "^(idx_\\d+_|i\\d+_)" + table + "$";
    if (!Pattern.matches(expected, name)) {
      out.add(
          new ConventionViolation(
              "N7",
              ChangesetReplay.qualify(change.schema(), name),
              (unique
                      ? "ユニークインデックスの名前をuk_{テーブル名}かuk_{連番}_{テーブル名}"
                      : "インデックスの名前をidx_{連番}_{テーブル名}かi{連番}_{テーブル名}")
                  + "にする（"
                  + NAMING_DOC
                  + "）。"));
    }
  }

  private static void sequenceName(
      final String schema,
      final String name,
      final Result replay,
      final List<ConventionViolation> out) {
    final Matcher matcher = SEQUENCE_NAME.matcher(name);
    if (!matcher.matches() || !replay.hasTable(schema, matcher.group(1))) {
      out.add(
          new ConventionViolation(
              "N8",
              ChangesetReplay.qualify(schema, name),
              "シーケンスの名前を、同じスキーマのテーブル名を使ったseq_{テーブル名}_{連番}にする（" + NAMING_DOC + "）。"));
    }
  }

  /** K3の違反。許可リストの1項目が1つの制約だけを許すよう、対象に制約のカラム（複数ならカンマ区切り）まで含める。 */
  private static ConventionViolation unique(
      final String schema, final String table, final String columns) {
    return new ConventionViolation(
        "K3",
        ChangesetReplay.qualify(schema, table) + "." + columns,
        "一意性はunique: trueを付けたcreateIndexで表し、ユニーク制約を使わない。"
            + "例外は理由を付けて許可リストに載せる（docs/database/postgresql-constraints-and-indexes.md）。");
  }
}
