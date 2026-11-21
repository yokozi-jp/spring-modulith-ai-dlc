package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.PolicyRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 業務の表をテナントで分ける規則（S3、S4、S5、S6）と、同じモジュールの表どうしの参照の外部キーの規則（K1）を、カタログの行から判定する。
 *
 * <p>DBに接続しない純粋な関数である。テナントで分ける表は、業務スキーマのうち {@link TenantIsolation#RLS_EXEMPT_SCHEMAS}
 * 以外のスキーマのテーブルとする。表の一覧は持たず、カタログから導く。
 */
final class SchemaTenantConventions {

  /** マルチテナントの規約の文書。 */
  private static final String MULTITENANCY_DOC = "docs/database/postgresql-multitenancy.md";

  /** 制約とインデックスの規約の文書。 */
  private static final String CONSTRAINTS_DOC =
      "docs/database/postgresql-constraints-and-indexes.md";

  /** テーブルの{@code relkind}（通常のテーブルとパーティションテーブル）。 */
  private static final Set<String> TABLE_KINDS = Set.of("r", "p");

  /** ポリシーの対象の{@code PUBLIC}。 */
  private static final List<String> PUBLIC_ROLE = List.of("public");

  private SchemaTenantConventions() {
    // 静的メソッドだけを持つ。
  }

  /**
   * テナントで分ける表を置くスキーマか。
   *
   * @param schema スキーマ名
   * @return 業務スキーマで、RLSの外に置くスキーマでなければ{@code true}
   */
  /* package */ static boolean isTenantScoped(final String schema) {
    return SchemaConventions.isBusinessSchema(schema)
        && !TenantIsolation.RLS_EXEMPT_SCHEMAS.contains(schema);
  }

  /**
   * S3：テナントで分ける表に、ENABLEとFORCEの行レベルセキュリティを付ける。RLSの外に置けるのは{@code modulith}と{@code tenancy}の表だけである。
   *
   * @param relations テーブルとビュー
   * @return 違反
   */
  /* package */ static List<ConventionViolation> rowSecurity(final List<RelationRow> relations) {
    return tenantTables(relations)
        .filter(row -> !row.rowSecurity() || !row.forceRowSecurity())
        .map(row ->
            new ConventionViolation(
                "S3",
                row.schema() + "." + row.name(),
                "ENABLE ROW LEVEL SECURITYとFORCE ROW LEVEL SECURITYを付ける。RLSの外に置けるのは"
                    + TenantIsolation.RLS_EXEMPT_SCHEMAS
                    + "の表だけである（"
                    + MULTITENANCY_DOC
                    + "）。"))
        .toList();
  }

  /**
   * S4：テナントで分ける表に、トランザクションのテナントを既定値にした、uuidのNOT NULLの{@code tenant_public_id}を持たせる。
   *
   * @param relations テーブルとビュー
   * @param columns カラム
   * @return 違反
   */
  /* package */ static List<ConventionViolation> tenantColumns(
      final List<RelationRow> relations, final List<ColumnRow> columns) {
    final Map<String, ColumnRow> tenantColumns =
        columns.stream()
            .filter(row -> TenantIsolation.TENANT_COLUMN.equals(row.column()))
            .collect(Collectors.toMap(row -> row.schema() + "." + row.table(), row -> row));
    return tenantTables(relations)
        .filter(row -> {
          final ColumnRow column = tenantColumns.get(row.schema() + "." + row.name());
          return column == null
              || !"uuid".equals(column.typeName())
              || !column.notNull()
              || !TenantIsolation.CATALOG_TENANT_DEFAULT.equals(column.defaultExpression());
        })
        .map(row ->
            new ConventionViolation(
                "S4",
                row.schema() + "." + row.name() + "." + TenantIsolation.TENANT_COLUMN,
                "uuidのNOT NULLで、既定値を"
                    + TenantIsolation.CATALOG_TENANT_DEFAULT
                    + "にしたテナントの列を持たせる（"
                    + MULTITENANCY_DOC
                    + "）。"))
        .toList();
  }

  /**
   * S5：テナントで分ける表のポリシーを、{@code PUBLIC}への許可のポリシー1つにし、{@code missing_ok}を使わずにテナントの設定と比べる。
   *
   * @param relations テーブルとビュー
   * @param policies ポリシー
   * @return 違反
   */
  /* package */ static List<ConventionViolation> policies(
      final List<RelationRow> relations, final List<PolicyRow> policies) {
    final Map<String, List<PolicyRow>> byTable =
        policies.stream().collect(Collectors.groupingBy(row -> row.schema() + "." + row.table()));
    return tenantTables(relations)
        .filter(row -> {
          final List<PolicyRow> rows =
              byTable.getOrDefault(row.schema() + "." + row.name(), List.of());
          return rows.size() != 1 || !isTenantPolicy(rows.get(0));
        })
        .map(row ->
            new ConventionViolation(
                "S5",
                row.schema() + "." + row.name(),
                "ポリシーは、PERMISSIVE、FOR ALL、TO PUBLIC、USING "
                    + TenantIsolation.CATALOG_POLICY_USING
                    + "、WITH CHECKなしの1つだけにする（"
                    + MULTITENANCY_DOC
                    + "）。"))
        .toList();
  }

  private static boolean isTenantPolicy(final PolicyRow row) {
    return "PERMISSIVE".equals(row.permissive())
        && "ALL".equals(row.command())
        && PUBLIC_ROLE.equals(row.roles())
        && TenantIsolation.CATALOG_POLICY_USING.equals(row.using())
        && row.withCheck() == null;
  }

  /**
   * S6：テナントで分ける表の、主キー以外のユニークインデックスを{@code tenant_public_id}から始める。
   *
   * @param indexes インデックス
   * @return 違反
   */
  /* package */ static List<ConventionViolation> uniqueIndexes(final List<IndexRow> indexes) {
    return indexes.stream()
        .filter(row -> isTenantScoped(row.schema()) && row.unique() && !row.primary())
        .filter(row -> !TenantIsolation.TENANT_COLUMN.equals(row.firstColumn()))
        .map(row ->
            new ConventionViolation(
                "S6",
                row.schema() + "." + row.name(),
                "ユニークインデックスをtenant_public_idから始め、一意性をテナントごとにする。"
                    + "先頭にないと、別のテナントの値の有無がINSERTの失敗から分かる（"
                    + CONSTRAINTS_DOC
                    + "）。"))
        .toList();
  }

  /**
   * K1：同じモジュールの表どうしの参照に外部キーを作る。テナントで分ける表は{@code (tenant_public_id,
   * 参照先の主キー)}、RLSの外の表は参照先の主キーだけの外部キーにする。
   *
   * <p>参照の列は、{@link
   * SchemaTableConventions#references}（P3）と同じく、同じスキーマのほかのテーブルの主キーと同じ名前の列とする。スキーマをまたぐ外部キーはP4が拒否する。
   *
   * @param relations テーブルとビュー
   * @param columns カラム
   * @param constraints 制約
   * @return 外部キーのない参照の列と、形の違う外部キーの違反
   */
  /* package */ static List<ConventionViolation> foreignKeys(
      final List<RelationRow> relations,
      final List<ColumnRow> columns,
      final List<ConstraintRow> constraints) {
    final Map<String, String> primaryKeys =
        relations.stream()
            .filter(row -> row.primaryKey() != null)
            .collect(
                Collectors.toMap(
                    row -> row.schema() + "." + row.name(),
                    row -> Objects.requireNonNull(row.primaryKey())));
    final Set<String> primaryKeyColumns =
        primaryKeys.entrySet().stream()
            .map(entry ->
                entry.getKey().substring(0, entry.getKey().indexOf('.') + 1) + entry.getValue())
            .collect(Collectors.toSet());
    final List<ConstraintRow> foreignKeys =
        constraints.stream()
            .filter(
                row -> "f".equals(row.type()) && SchemaConventions.isBusinessSchema(row.schema()))
            .filter(row -> row.schema().equals(row.referencedSchema()))
            .toList();
    final List<ConventionViolation> violations = new ArrayList<>();
    foreignKeys.stream()
        .filter(row -> !hasExpectedShape(row, primaryKeys))
        .map(row ->
            new ConventionViolation(
                "K1",
                row.schema() + "." + row.name(),
                expectedShape(row.schema())
                    + "の外部キーにし、参照先に同じ組のユニークインデックスを作る（"
                    + CONSTRAINTS_DOC
                    + "）。"))
        .forEach(violations::add);
    columns.stream()
        .filter(row -> SchemaConventions.isBusinessSchema(row.schema()))
        .filter(row -> primaryKeyColumns.contains(row.schema() + "." + row.column()))
        .filter(row -> !row.column().equals(primaryKeys.get(row.schema() + "." + row.table())))
        .filter(row ->
            foreignKeys.stream()
                .noneMatch(fk ->
                    fk.schema().equals(row.schema())
                        && fk.table().equals(row.table())
                        && fk.columns().contains(row.column())))
        .map(row ->
            new ConventionViolation(
                "K1",
                row.schema() + "." + row.table() + "." + row.column(),
                "同じモジュールの表を指す列に、"
                    + expectedShape(row.schema())
                    + "の外部キーを作る（"
                    + CONSTRAINTS_DOC
                    + "）。"))
        .forEach(violations::add);
    return violations;
  }

  private static boolean hasExpectedShape(
      final ConstraintRow row, final Map<String, String> primaryKeys) {
    final Optional<String> referencedKey =
        Optional.ofNullable(row.referencedTable())
            .map(table -> primaryKeys.get(row.referencedSchema() + "." + table));
    if (referencedKey.isEmpty()) {
      return false;
    }
    final List<String> expected =
        Stream.concat(
                isTenantScoped(row.schema())
                    ? Stream.of(TenantIsolation.TENANT_COLUMN)
                    : Stream.empty(),
                Stream.of(referencedKey.get()))
            .toList();
    return expected.equals(row.columns()) && expected.equals(row.referencedColumns());
  }

  private static String expectedShape(final String schema) {
    return isTenantScoped(schema) ? "(tenant_public_id, 参照先の主キー)" : "参照先の主キーだけ";
  }

  private static Stream<RelationRow> tenantTables(final List<RelationRow> relations) {
    return relations.stream()
        .filter(row -> isTenantScoped(row.schema()) && TABLE_KINDS.contains(row.relkind()));
  }
}
