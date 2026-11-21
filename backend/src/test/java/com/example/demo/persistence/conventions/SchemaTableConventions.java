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
import java.util.stream.Collectors;

/**
 * 業務テーブルの制約、インデックス、主キー、カラムの規則（O2、K2、K3、K7、K8、K9、P1、P2、P3、P4、T1、T2、T11、T13）を、カタログの行から判定する。
 *
 * <p>DBに接続しない純粋な関数である。各関数は{@link SchemaConventions#isBusinessSchema(String)}で業務スキーマの行だけを判定する。
 */
@SuppressWarnings("PMD.TooManyMethods")
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
          "c", Map.entry("K2", "CHECK制約を使わず、値の検証はアプリケーションで行う"),
          "u", Map.entry("K3", "ユニーク制約ではなく、ユニークインデックスで一意性を表す"));

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
        .map(row ->
            new ConventionViolation(
                "O2",
                row.schema() + "." + row.table() + "." + row.name(),
                "トリガーを使わない（docs/database/postgresql-database-objects.md）。"))
        .toList();
  }

  /**
   * K2、K3：CHECK、ユニークの制約を使わない。
   *
   * @param rows 制約
   * @return 違反
   */
  /* package */ static List<ConventionViolation> constraints(final List<ConstraintRow> rows) {
    return rows.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .filter(row -> BANNED_CONSTRAINTS.containsKey(row.type()))
        .map(row ->
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
        .filter(row -> row.isTable() && row.primaryKey() == null)
        .map(row ->
            new ConventionViolation(
                "P2",
                row.schema() + "." + row.name(),
                "業務テーブルには主キーを付ける（" + PRIMARY_KEYS_DOC + "）。"))
        .toList();
  }

  /**
   * P3：別のモジュールの行を指す列は、uuidの{@code <対象>_public_id}にする。
   *
   * <p>名前が{@code _id}で終わる列は、同じスキーマのテーブルの主キーと同じ名前のときだけ、同じモジュールの行を指すとみなす。 それ以外の{@code
   * _id}の列は別のモジュールの行を指すとみなし、{@code _public_id}の名前にさせる。
   *
   * @param relations テーブルとビュー
   * @param columns カラム
   * @return 違反
   */
  /* package */ static List<ConventionViolation> references(
      final List<RelationRow> relations, final List<ColumnRow> columns) {
    final Set<String> primaryKeys = primaryKeyColumns(relations);
    return columns.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .filter(row -> !isAllowedIdColumn(row, primaryKeys))
        .map(row ->
            new ConventionViolation(
                "P3",
                row.schema() + "." + row.table() + "." + row.column(),
                "別のモジュールの行を指す列は、主キーではなくuuidの<対象>_public_idにする（" + PRIMARY_KEYS_DOC + "）。"))
        .toList();
  }

  /**
   * 主キーの列を、{@code スキーマ.列}の集合で返す。
   *
   * @param relations テーブルとビュー
   * @return 主キーを持つテーブルの、{@code スキーマ.主キーの列}
   */
  /* package */ static Set<String> primaryKeyColumns(final List<RelationRow> relations) {
    return relations.stream()
        .filter(row -> row.primaryKey() != null)
        .map(row -> row.schema() + "." + row.primaryKey())
        .collect(Collectors.toSet());
  }

  /**
   * 名前が{@code _id}で終わらないか、行を指さない共通カラムか、同じスキーマの主キーを指すか、uuidの{@code public_id}か{@code
   * _public_id}なら{@code true}。
   */
  private static boolean isAllowedIdColumn(final ColumnRow row, final Set<String> primaryKeys) {
    final String column = row.column();
    if ("public_id".equals(column) || column.endsWith("_public_id")) {
      return "uuid".equals(row.typeName());
    }
    return !column.endsWith("_id")
        || ChangesetCommonColumnRules.COMMON_COLUMN_NAMES.contains(column)
        || primaryKeys.contains(row.schema() + "." + column);
  }

  /**
   * P4：スキーマをまたぐ外部キーを作らない。
   *
   * @param rows 制約
   * @return 違反
   */
  /* package */ static List<ConventionViolation> crossSchemaForeignKeys(
      final List<ConstraintRow> rows) {
    return rows.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .filter(row -> "f".equals(row.type()) && !row.schema().equals(row.referencedSchema()))
        .map(row ->
            new ConventionViolation(
                "P4",
                row.schema() + "." + row.name(),
                "別のスキーマ（"
                    + row.referencedSchema()
                    + "）のテーブルを外部キーで参照せず、uuidの<対象>_public_idで指す（"
                    + PRIMARY_KEYS_DOC
                    + "）。"))
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

  /** T13：uuidのDEFAULT。テナントの列の、トランザクションのテナントを入れる既定値はIDの採番ではないため除く。 */
  private static void uuidDefault(
      final ColumnRow row, final String target, final List<ConventionViolation> violations) {
    final String defaultExpression = row.defaultExpression();
    final boolean tenantDefault =
        TenantIsolation.TENANT_COLUMN.equals(row.column())
            && TenantIsolation.CATALOG_TENANT_DEFAULT.equals(defaultExpression);
    if ("uuid".equals(row.typeName()) && defaultExpression != null && !tenantDefault) {
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
