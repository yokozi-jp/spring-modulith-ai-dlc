package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.PolicyRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * テナントで分ける表の規則と、同じモジュールの外部キーの規則（{@link SchemaTenantConventions}）を、違反を含む行のデータで検証する。
 *
 * <p>DBに接続しない。各テストは違反の規則の記号と対象を、{@code 規則 対象}のキーの集合で確かめる。
 */
class SchemaTenantConventionsTest {

  /** テナントで分ける業務スキーマ。 */
  private static final String ITEM = "item";

  /** RLSの外に置くスキーマ。 */
  private static final String TENANCY = "tenancy";

  /** Spring Modulithのスキーマ。 */
  private static final String MODULITH = "modulith";

  /** 業務テーブル。 */
  private static final String TABLE = "m_item";

  /** 業務テーブルの主キー。 */
  private static final String ITEM_ID = "item_id";

  /** uuidの型名。 */
  private static final String UUID = "uuid";

  /** bigintの型名。 */
  private static final String BIGINT = "bigint";

  /** btreeのアクセスメソッド。 */
  private static final String BTREE = "btree";

  /** 外に出すID。 */
  private static final String PUBLIC_ID = "public_id";

  /** ポリシーの対象のPUBLIC。 */
  private static final List<String> PUBLIC = List.of("public");

  /** ポリシーのすべての操作。 */
  private static final String ALL = "ALL";

  /** 許可のポリシー。 */
  private static final String PERMISSIVE = "PERMISSIVE";

  /** テナントの列。 */
  private static final String TENANT = TenantIsolation.TENANT_COLUMN;

  /** テナントで分ける表の行レベルセキュリティ、テナントの列、ポリシー、ユニークインデックス。 */
  /* package */ @Nested
  class TenantIsolationRulesTest {

    @Test
    @DisplayName("S3：ENABLEかFORCEのない業務の表を拒否し、modulithとtenancyの表は判定しない")
    void rejectsBusinessTablesWithoutEnabledAndForcedRowSecurity() {
      final List<RelationRow> rows =
          List.of(
              new RelationRow(ITEM, "m_none", "r", ITEM_ID, null, false, false),
              new RelationRow(ITEM, "m_enabled", "r", ITEM_ID, null, true, false),
              tenantTable(ITEM, TABLE, ITEM_ID),
              new RelationRow(ITEM, "v_item", "v", null, null),
              new RelationRow(TENANCY, "m_tenant", "r", "tenant_id", null),
              new RelationRow(MODULITH, "event_publication", "r", "id", null));

      assertThat(keys(SchemaTenantConventions.rowSecurity(rows)))
          .as("テーブル: %s", rows)
          .containsExactlyInAnyOrder("S3 item.m_none", "S3 item.m_enabled");
    }

    @Test
    @DisplayName("S4：テナントの列がない、uuidでない、NULLを許す、既定値が違う業務の表を拒否する")
    void rejectsBusinessTablesWithoutConformingTenantColumn() {
      final List<RelationRow> relations =
          List.of(
              tenantTable(ITEM, "m_missing", ITEM_ID),
              tenantTable(ITEM, "m_text", ITEM_ID),
              tenantTable(ITEM, "m_nullable", ITEM_ID),
              tenantTable(ITEM, "m_no_default", ITEM_ID),
              tenantTable(ITEM, TABLE, ITEM_ID),
              new RelationRow(TENANCY, "m_tenant", "r", "tenant_id", null));
      final String tenant = TENANT;
      final String tenantDefault = TenantIsolation.CATALOG_TENANT_DEFAULT;
      final List<ColumnRow> columns =
          List.of(
              new ColumnRow(ITEM, "m_text", tenant, "text", "b", "", "", tenantDefault, true),
              new ColumnRow(ITEM, "m_nullable", tenant, UUID, "b", "", "", tenantDefault, false),
              new ColumnRow(ITEM, "m_no_default", tenant, UUID, "b", "", "", null, true),
              tenantColumn(ITEM, TABLE));

      assertThat(keys(SchemaTenantConventions.tenantColumns(relations, columns)))
          .containsExactlyInAnyOrder(
              "S4 item.m_missing.tenant_public_id",
              "S4 item.m_text.tenant_public_id",
              "S4 item.m_nullable.tenant_public_id",
              "S4 item.m_no_default.tenant_public_id");
    }

    @Test
    @DisplayName("S5：ポリシーがない、PUBLICでない、missing_okを使う、RESTRICTIVE、2つある業務の表を拒否する")
    void rejectsBusinessTablesWithoutSingleTenantPolicy() {
      final List<RelationRow> relations =
          List.of(
              tenantTable(ITEM, "m_none", ITEM_ID),
              tenantTable(ITEM, "m_role", ITEM_ID),
              tenantTable(ITEM, "m_missing_ok", ITEM_ID),
              tenantTable(ITEM, "m_restrictive", ITEM_ID),
              tenantTable(ITEM, "m_two", ITEM_ID),
              tenantTable(ITEM, TABLE, ITEM_ID),
              new RelationRow(TENANCY, "m_tenant", "r", "tenant_id", null));
      final String using = TenantIsolation.CATALOG_POLICY_USING;
      final List<PolicyRow> policies =
          List.of(
              new PolicyRow(
                  ITEM, "m_role", "p", PERMISSIVE, List.of("product_module"), ALL, using, null),
              new PolicyRow(
                  ITEM,
                  "m_missing_ok",
                  "p",
                  PERMISSIVE,
                  PUBLIC,
                  ALL,
                  "(tenant_public_id = (current_setting('app.current_tenant'::text, true))::uuid)",
                  null),
              new PolicyRow(ITEM, "m_restrictive", "p", "RESTRICTIVE", PUBLIC, ALL, using, null),
              tenantPolicy(ITEM, "m_two"),
              tenantPolicy(ITEM, "m_two"),
              tenantPolicy(ITEM, TABLE));

      assertThat(keys(SchemaTenantConventions.policies(relations, policies)))
          .containsExactlyInAnyOrder(
              "S5 item.m_none",
              "S5 item.m_role",
              "S5 item.m_missing_ok",
              "S5 item.m_restrictive",
              "S5 item.m_two");
    }

    @Test
    @DisplayName("S6：tenant_public_idから始まらない業務の表のユニークインデックスを拒否し、主キーとtenancyは判定しない")
    void rejectsUniqueIndexesNotLedByTenant() {
      final List<IndexRow> rows =
          List.of(
              uniqueIndex(ITEM, "uk_m_item", PUBLIC_ID, false),
              uniqueIndex(ITEM, "uk_2_m_item", TENANT, false),
              uniqueIndex(ITEM, "pk_m_item", ITEM_ID, true),
              uniqueIndex(ITEM, "uk_3_m_item", null, false),
              uniqueIndex(TENANCY, "uk_m_tenant", PUBLIC_ID, false),
              new IndexRow(
                  ITEM,
                  TABLE,
                  "idx_1_m_item",
                  BTREE,
                  false,
                  1,
                  1,
                  false,
                  false,
                  false,
                  false,
                  null));

      assertThat(keys(SchemaTenantConventions.uniqueIndexes(rows)))
          .as("インデックス: %s", rows)
          .containsExactlyInAnyOrder("S6 item.uk_m_item", "S6 item.uk_3_m_item");
    }

    private IndexRow uniqueIndex(
        final String schema, final String name, final String firstColumn, final boolean primary) {
      return new IndexRow(
          schema, TABLE, name, BTREE, primary, 2, 2, false, false, false, true, firstColumn);
    }
  }

  /** 同じモジュールの表どうしの参照の外部キー。 */
  /* package */ @Nested
  class ForeignKeyRulesTest {

    /** 注文のスキーマ。 */
    private static final String ORDERING = "ordering";

    /** 注文の主キー。 */
    private static final String ORDER_ID = "order_id";

    /** 利用者の主キー。 */
    private static final String USER_ID = "user_id";

    /** 注文の表。 */
    private static final String T_ORDER = "t_order";

    /** 明細の表。 */
    private static final String T_ORDER_LINE = "t_order_line";

    /** ID連携の表。 */
    private static final String M_USER_IDENTITY = "m_user_identity";

    /** 注文、明細、利用者、ID連携のテーブル。 */
    private final List<RelationRow> relations =
        List.of(
            tenantTable(ORDERING, T_ORDER, ORDER_ID),
            tenantTable(ORDERING, T_ORDER_LINE, "order_line_id"),
            new RelationRow(TENANCY, "m_user", "r", USER_ID, null),
            new RelationRow(TENANCY, M_USER_IDENTITY, "r", "user_identity_id", null));

    /** 明細から注文、ID連携から利用者を指す列。 */
    private final List<ColumnRow> columns =
        List.of(
            new ColumnRow(ORDERING, T_ORDER, ORDER_ID, BIGINT, "b", "a", "", null, true),
            new ColumnRow(ORDERING, T_ORDER_LINE, ORDER_ID, BIGINT, "b", "", "", null, true),
            new ColumnRow(TENANCY, M_USER_IDENTITY, USER_ID, BIGINT, "b", "", "", null, true));

    @Test
    @DisplayName("K1：テナントで分ける表は複合の、RLSの外の表は主キーだけの外部キーを受け入れる")
    void acceptsCompositeForeignKeyForTenantTablesAndSingleColumnOtherwise() {
      final List<ConstraintRow> constraints =
          List.of(
              foreignKey(
                  ORDERING,
                  T_ORDER_LINE,
                  "fk_t_order_line",
                  List.of(TENANT, ORDER_ID),
                  T_ORDER,
                  List.of(TENANT, ORDER_ID)),
              foreignKey(
                  TENANCY,
                  M_USER_IDENTITY,
                  "fk_m_user_identity",
                  List.of(USER_ID),
                  "m_user",
                  List.of(USER_ID)));

      assertThat(SchemaTenantConventions.foreignKeys(relations, columns, constraints)).isEmpty();
    }

    @Test
    @DisplayName("K1：外部キーのない同じモジュールの参照を拒否する")
    void rejectsSameModuleReferencesWithoutForeignKey() {
      assertThat(keys(SchemaTenantConventions.foreignKeys(relations, columns, List.of())))
          .containsExactlyInAnyOrder(
              "K1 ordering.t_order_line.order_id", "K1 tenancy.m_user_identity.user_id");
    }

    @Test
    @DisplayName("K1：テナントで分ける表の単独の列の外部キーと、RLSの外の表の複合の外部キーを拒否する")
    void rejectsForeignKeysOfTheWrongShape() {
      final List<ConstraintRow> constraints =
          List.of(
              foreignKey(
                  ORDERING,
                  T_ORDER_LINE,
                  "fk_t_order_line",
                  List.of(ORDER_ID),
                  T_ORDER,
                  List.of(ORDER_ID)),
              foreignKey(
                  TENANCY,
                  M_USER_IDENTITY,
                  "fk_m_user_identity",
                  List.of(TENANT, USER_ID),
                  "m_user",
                  List.of(TENANT, USER_ID)));

      assertThat(keys(SchemaTenantConventions.foreignKeys(relations, columns, constraints)))
          .containsExactlyInAnyOrder(
              "K1 ordering.fk_t_order_line", "K1 tenancy.fk_m_user_identity");
    }

    private static ConstraintRow foreignKey(
        final String schema,
        final String table,
        final String name,
        final List<String> foreignKeyColumns,
        final String referencedTable,
        final List<String> referencedColumns) {
      return new ConstraintRow(
          schema, table, name, "f", schema, foreignKeyColumns, referencedTable, referencedColumns);
    }
  }

  /** RLSのENABLEとFORCEを付けたテーブル。 */
  private static RelationRow tenantTable(
      final String schema, final String table, final String primaryKey) {
    return new RelationRow(schema, table, "r", primaryKey, null, true, true);
  }

  /** トランザクションのテナントを既定値にした、uuidのNOT NULLのテナントの列。 */
  private static ColumnRow tenantColumn(final String schema, final String table) {
    return new ColumnRow(
        schema, table, TENANT, UUID, "b", "", "", TenantIsolation.CATALOG_TENANT_DEFAULT, true);
  }

  /** テナントの設定と行のテナントを比べる、PUBLICへの許可のポリシー。 */
  private static PolicyRow tenantPolicy(final String schema, final String table) {
    return new PolicyRow(
        schema,
        table,
        TenantIsolation.POLICY_NAME,
        PERMISSIVE,
        PUBLIC,
        ALL,
        TenantIsolation.CATALOG_POLICY_USING,
        null);
  }

  private static Set<String> keys(final List<ConventionViolation> violations) {
    return violations.stream().map(ConventionViolation::key).collect(Collectors.toSet());
  }
}
