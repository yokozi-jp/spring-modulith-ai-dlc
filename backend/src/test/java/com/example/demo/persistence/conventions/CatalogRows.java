package com.example.demo.persistence.conventions;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.PolicyRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** スキーマ検査の単体テストが組み立てる、カタログの行と、違反のキー。 */
final class CatalogRows {

  /** 業務スキーマ。 */
  /* package */ static final String ITEM = "item";

  /** Spring Modulithのスキーマ。 */
  /* package */ static final String MODULITH = "modulith";

  /** 業務テーブル。 */
  /* package */ static final String TABLE = "m_item";

  /** 業務テーブルの主キー。 */
  /* package */ static final String ITEM_ID = "item_id";

  /** uuidの型名。 */
  /* package */ static final String UUID = "uuid";

  /** bigintの型名。 */
  /* package */ static final String BIGINT = "bigint";

  /** btreeのアクセスメソッド。 */
  /* package */ static final String BTREE = "btree";

  private CatalogRows() {}

  /** 違反を、{@code 規則 対象}のキーの集合にする。 */
  /* package */ static Set<String> keys(final List<ConventionViolation> violations) {
    return violations.stream().map(ConventionViolation::key).collect(Collectors.toSet());
  }

  /** {@link #TABLE}の、式、述語、排他制約のない、ユニークでないインデックス{@code idx_1_m_item}。 */
  /* package */ static IndexRow index(
      final String schema,
      final String accessMethod,
      final boolean primary,
      final int keyColumnCount,
      final int totalColumnCount) {
    return new IndexRow(
        schema,
        TABLE,
        "idx_1_m_item",
        accessMethod,
        primary,
        keyColumnCount,
        totalColumnCount,
        false,
        false,
        false,
        false,
        null);
  }

  /** RLSのENABLEとFORCEと、論理名のコメントを付けたテーブル。 */
  /* package */ static RelationRow tenantTable(
      final String schema, final String table, final String primaryKey) {
    return new RelationRow(schema, table, "r", primaryKey, null, true, true, "品目");
  }

  /** トランザクションのテナントを既定値にした、uuidのNOT NULLの、論理名のコメントを付けたテナントの列。 */
  /* package */ static ColumnRow tenantColumn(final String schema, final String table) {
    return new ColumnRow(
        schema,
        table,
        TenantIsolation.TENANT_COLUMN,
        UUID,
        "b",
        "",
        "",
        TenantIsolation.CATALOG_TENANT_DEFAULT,
        true,
        "テナント公開ID");
  }

  /** テナントの設定と行のテナントを比べる、PUBLICへの許可のポリシー。 */
  /* package */ static PolicyRow tenantPolicy(final String schema, final String table) {
    return new PolicyRow(
        schema,
        table,
        TenantIsolation.POLICY_NAME,
        "PERMISSIVE",
        List.of("public"),
        "ALL",
        TenantIsolation.CATALOG_POLICY_USING,
        null);
  }
}
