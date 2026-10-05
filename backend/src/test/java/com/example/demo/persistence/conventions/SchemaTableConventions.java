package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TriggerRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 業務テーブルの制約、インデックス、主キー、カラムの規則（O2、K1、K2、K3、K7、K8、K9、P1、P2、T1、T2、T11、T13）を、カタログの行から判定する。
 *
 * <p>DBに接続しない純粋な関数である。各関数は{@link SchemaConventions#isBusinessSchema(String)}で業務スキーマの行だけを判定する。
 */
final class SchemaTableConventions {

  /** 制約とインデックスの規約の文書。 */
  private static final String CONSTRAINTS_DOC =
      "docs/database/postgresql-constraints-and-indexes.md";

  /** データ型の規約の文書。 */
  private static final String DATA_TYPES_DOC = "docs/database/postgresql-data-types.md";

  /** 主キーの規約の文書。 */
  private static final String PRIMARY_KEYS_DOC = "docs/database/postgresql-primary-keys.md";

  /** 禁止する制約の{@code contype}と、規則の記号と内容。PG18の{@code n}（NOT NULL）は含めない。 */
  private static final Map<String, Map.Entry<String, String>> BANNED_CONSTRAINTS =
      Map.of(
          "f", Map.entry("K1", "外部キー制約を作らず、参照整合性はアプリケーションで保つ"),
          "c", Map.entry("K2", "CHECK制約を使わず、値の検証はアプリケーションで行う"),
          "u", Map.entry("K3", "ユニーク制約ではなく、ユニークインデックスで一意性を表す"));

  /** テーブルの{@code relkind}（通常のテーブルとパーティションテーブル）。 */
  private static final Set<String> TABLE_KINDS = Set.of("r", "p");

  private SchemaTableConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * O2：トリガーを使わない。
   *
   * @param rows 内部でないトリガー
   * @return 違反
   */
  /* package */ static List<ConventionViolation> triggers(final List<TriggerRow> rows) {
    return rows.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .map(
            row ->
                new ConventionViolation(
                    "O2",
                    row.schema() + "." + row.table() + "." + row.name(),
                    "トリガーを使わない（docs/database/postgresql-database-objects.md）。"))
        .toList();
  }

  /**
   * K1、K2、K3：外部キー、CHECK、ユニークの制約を使わない。
   *
   * @param rows 制約
   * @return 違反
   */
  /* package */ static List<ConventionViolation> constraints(final List<ConstraintRow> rows) {
    return rows.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .filter(row -> BANNED_CONSTRAINTS.containsKey(row.type()))
        .map(
            row ->
                new ConventionViolation(
                    BANNED_CONSTRAINTS.get(row.type()).getKey(),
                    row.schema() + "." + row.name(),
                    BANNED_CONSTRAINTS.get(row.type()).getValue() + "（" + CONSTRAINTS_DOC + "）。"))
        .toList();
  }

  /**
   * K7、K8、K9、P1：インデックスの種類と構成。
   *
   * @param rows インデックス
   * @return 違反
   */
  /* package */ static List<ConventionViolation> indexes(final List<IndexRow> rows) {
    final List<ConventionViolation> violations = new ArrayList<>();
    rows.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .forEach(row -> index(row, violations));
    return violations;
  }

  private static void index(final IndexRow row, final List<ConventionViolation> violations) {
    final String target = row.schema() + "." + row.name();
    final boolean exclusionGist =
        "gist".equals(row.accessMethod()) && row.backsExclusionConstraint();
    if (!"btree".equals(row.accessMethod()) && !exclusionGist) {
      violations.add(
          new ConventionViolation(
              "K7",
              target,
              row.accessMethod()
                  + "のインデックスを使わずbtreeにする。全文検索のGINは理由を付けて許可リストに載せる（"
                  + CONSTRAINTS_DOC
                  + "）。"));
    }
    if (row.hasExpressions()) {
      violations.add(
          new ConventionViolation(
              "K8", target, "式インデックスを使わず、式の結果をカラムに持つ（" + CONSTRAINTS_DOC + "）。"));
    }
    if (row.hasPredicate() || row.totalColumnCount() > row.keyColumnCount()) {
      violations.add(
          new ConventionViolation(
              "K9",
              target,
              "部分インデックスとINCLUDE付きインデックスは、レビューで合意して許可リストに載せてから使う（" + CONSTRAINTS_DOC + "）。"));
    }
    if (row.primary() && row.keyColumnCount() > 1) {
      violations.add(
          new ConventionViolation("P1", target, "複合主キーを使わない（" + PRIMARY_KEYS_DOC + "）。"));
    }
  }

  /**
   * P2：すべてのテーブルに主キーがある。
   *
   * @param rows テーブルとビュー
   * @return 違反
   */
  /* package */ static List<ConventionViolation> primaryKeys(final List<RelationRow> rows) {
    return rows.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .filter(row -> TABLE_KINDS.contains(row.relkind()) && !row.hasPrimaryKey())
        .map(
            row ->
                new ConventionViolation(
                    "P2",
                    row.schema() + "." + row.name(),
                    "業務テーブルには主キーを付ける（" + PRIMARY_KEYS_DOC + "）。"))
        .toList();
  }

  /**
   * T1、T2、T11、T13：serial、IDENTITYの種類、生成列、uuidのDEFAULT。
   *
   * @param rows カラム
   * @return 違反
   */
  /* package */ static List<ConventionViolation> columns(final List<ColumnRow> rows) {
    final List<ConventionViolation> violations = new ArrayList<>();
    for (final ColumnRow row : rows) {
      if (!SchemaConventions.isBusinessSchema(row.schema())) {
        continue;
      }
      final String target = row.schema() + "." + row.table() + "." + row.column();
      final String defaultExpression = row.defaultExpression();
      if (defaultExpression != null && defaultExpression.startsWith("nextval(")) {
        violations.add(
            new ConventionViolation(
                "T1",
                target,
                "serialとbigserialを使わず、bigint GENERATED ALWAYS AS IDENTITYにする（"
                    + DATA_TYPES_DOC
                    + "）。"));
      }
      final boolean byDefault = "d".equals(row.identity());
      final boolean notBigint = "a".equals(row.identity()) && !"bigint".equals(row.typeName());
      if (byDefault || notBigint) {
        violations.add(
            new ConventionViolation(
                "T2",
                target,
                "IDENTITY列はbigint GENERATED ALWAYS AS IDENTITYにする（" + DATA_TYPES_DOC + "）。"));
      }
      if (!row.generated().isEmpty()) {
        violations.add(new ConventionViolation("T11", target, "生成列を使わない（" + DATA_TYPES_DOC + "）。"));
      }
      uuidDefault(row, target, violations);
    }
    return violations;
  }

  /** T13：uuidのDEFAULT。 */
  private static void uuidDefault(
      final ColumnRow row, final String target, final List<ConventionViolation> violations) {
    final String defaultExpression = row.defaultExpression();
    if ("uuid".equals(row.typeName()) && defaultExpression != null) {
      violations.add(
          new ConventionViolation(
              "T13",
              target,
              "uuidはアプリケーションでRepositoryのnextId()から採番し、DEFAULT（"
                  + defaultExpression
                  + "）を外す（"
                  + PRIMARY_KEYS_DOC
                  + "）。"));
    }
  }
}
