package com.example.demo.persistence.conventions;

import static com.example.demo.persistence.conventions.CatalogRows.BIGINT;
import static com.example.demo.persistence.conventions.CatalogRows.BTREE;
import static com.example.demo.persistence.conventions.CatalogRows.ITEM;
import static com.example.demo.persistence.conventions.CatalogRows.ITEM_ID;
import static com.example.demo.persistence.conventions.CatalogRows.MODULITH;
import static com.example.demo.persistence.conventions.CatalogRows.TABLE;
import static com.example.demo.persistence.conventions.CatalogRows.UUID;
import static com.example.demo.persistence.conventions.CatalogRows.columnRow;
import static com.example.demo.persistence.conventions.CatalogRows.index;
import static com.example.demo.persistence.conventions.CatalogRows.keys;
import static com.example.demo.persistence.conventions.CatalogRows.tenantColumn;
import static com.example.demo.persistence.conventions.CatalogRows.tenantPolicy;
import static com.example.demo.persistence.conventions.CatalogRows.tenantTable;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.FunctionRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.LogicalName;
import com.example.demo.persistence.conventions.CatalogSnapshot.ModuleRoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.Origin;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.SchemaPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TriggerRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TypeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ViewDependencyRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * スキーマ検査の純粋な判定（{@link SchemaConventions}、{@link SchemaTableConventions}、{@link
 * SchemaPrivilegeConventions}）を、違反を含む行のデータで検証する。
 *
 * <p>DBに接続しない。各テストは違反の規則の記号と対象を、{@code 規則 対象}のキーの集合で確かめる。
 */
// 共有の行と定数（CatalogRows）を static import で読む。
@SuppressWarnings("PMD.TooManyStaticImports")
class SchemaConventionsTest {

  /** Liquibaseの管理テーブルのスキーマ。 */
  private static final String LIQUIBASE = "liquibase";

  /** integerの型名。 */
  private static final String INTEGER = "integer";

  /** 商品のモジュールとスキーマの名前。 */
  private static final String PRODUCT = "product";

  /** 商品のモジュールのロール。 */
  private static final String PRODUCT_ROLE = "product_module";

  /** トリガー名。 */
  private static final String TRIGGER = "trg";

  /** アプリロール。 */
  private static final String ROLE = "demo";

  /** マイグレーションロール。 */
  private static final String MIGRATION_ROLE = "demo_migration";

  @Test
  @DisplayName("規約を満たす行だけなら違反がない")
  void conformingSnapshotHasNoViolations() {
    final CatalogSnapshot snapshot =
        new CatalogSnapshot(
            List.of(tenantTable(ITEM, TABLE, ITEM_ID), relation(MODULITH, "r", false, null)),
            List.of(
                columnRow(ITEM, TABLE, ITEM_ID, new LogicalName("品目ID", Origin.PLAIN)),
                tenantColumn(ITEM, TABLE),
                column(MODULITH, INTEGER, "b", "", "", "nextval('x'::regclass)")),
            List.of(
                new ConstraintRow(ITEM, TABLE, "pk_m_item", "p", null),
                new ConstraintRow(ITEM, TABLE, "m_item_item_code_not_null", "n", null)),
            List.of(
                index(ITEM, BTREE, true, 1, 1),
                index(MODULITH, "hash", false, 1, 1),
                index(LIQUIBASE, BTREE, false, 2, 3)),
            List.of(new TriggerRow(MODULITH, TABLE, TRIGGER)),
            List.of(new TypeRow(LIQUIBASE, "status", "e")),
            List.of(new FunctionRow(MODULITH, "modulith.fn()")),
            List.of(),
            List.of("plpgsql", "btree_gist"),
            0,
            cleanRole(),
            new RoleRow(MIGRATION_ROLE, false, false, false, false, false, true),
            List.of(
                new SchemaPrivilegeRow(ITEM, false, false, false),
                new SchemaPrivilegeRow(MODULITH, true, false, false),
                new SchemaPrivilegeRow(LIQUIBASE, false, false, false)),
            List.of(
                relationPrivilege(MODULITH, true, false, false),
                relationPrivilege(ITEM, false, false, false),
                relationPrivilege(LIQUIBASE, false, false, false)),
            List.of(),
            List.of(tenantPolicy(ITEM, TABLE)));

    assertThat(SchemaConventions.check(snapshot, List.of())).as("違反").isEmpty();
  }

  @Test
  @DisplayName("許可リストに載せた違反を抑止し、使われない項目を報告する")
  void allowlistSuppressesMatchesAndReportsUnusedEntries() {
    final List<ConventionViolation> found =
        List.of(new ConventionViolation("K7", "item.idx_1_m_item", "gin"));
    final List<AllowlistEntry> allowlist =
        List.of(
            new AllowlistEntry("K7", "item.idx_1_m_item", "全文検索"),
            new AllowlistEntry("K7", "item.idx_2_m_item", "使われない"));

    assertThat(keys(AllowlistEntry.apply(found, allowlist)))
        .as("許可リスト: %s", allowlist)
        .containsExactly("ALLOWLIST K7 item.idx_2_m_item");
  }

  @Test
  @DisplayName("スキーマ検査の許可リストの各項目に理由がある")
  void allowlistEntriesHaveReasons() {
    assertThat(SchemaConventions.ALLOWLIST)
        .as("SchemaConventions.ALLOWLIST")
        .allSatisfy(entry -> assertThat(entry.reason()).as("%s の理由", entry.key()).isNotBlank());
  }

  /** トリガーと制約。 */
  /* package */ @Nested
  class ConstraintRulesTest {

    @Test
    @DisplayName("O2：業務テーブルのトリガーを拒否する")
    void rejectsTriggers() {
      assertThat(
              keys(SchemaTableConventions.triggers(List.of(new TriggerRow(ITEM, TABLE, TRIGGER)))))
          .containsExactly("O2 item.m_item.trg");
    }

    @Test
    @DisplayName("K2、K3：CHECKとユニークの制約を拒否し、外部キーはこの規則で判定しない")
    void rejectsCheckAndUniqueConstraints() {
      final List<ConstraintRow> rows =
          List.of(
              new ConstraintRow(ITEM, TABLE, "fk_x", "f", ITEM),
              new ConstraintRow(ITEM, TABLE, "chk_x", "c", null),
              new ConstraintRow(ITEM, TABLE, "uq_x", "u", null));

      assertThat(keys(SchemaTableConventions.constraints(rows)))
          .as("制約: %s", rows)
          .containsExactlyInAnyOrder("K2 item.chk_x", "K3 item.uq_x");
    }

    @Test
    @DisplayName("K2：PG18のNOT NULL制約（contype='n'）をCHECKとみなさない")
    void ignoresNotNullConstraints() {
      assertThat(
              SchemaTableConventions.constraints(
                  List.of(new ConstraintRow(ITEM, TABLE, "m_item_x_not_null", "n", null))))
          .isEmpty();
    }

    @Test
    @DisplayName("modulithとliquibaseのトリガーと制約を判定しない")
    void ignoresFrameworkSchemas() {
      assertThat(
              SchemaTableConventions.constraints(
                  List.of(
                      new ConstraintRow(MODULITH, TABLE, "fk_x", "f", MODULITH),
                      new ConstraintRow(LIQUIBASE, TABLE, "chk_x", "c", null))))
          .isEmpty();
      assertThat(
              SchemaTableConventions.triggers(
                  List.of(
                      new TriggerRow(MODULITH, TABLE, TRIGGER),
                      new TriggerRow(LIQUIBASE, TABLE, TRIGGER))))
          .isEmpty();
    }
  }

  /** インデックスと主キー。 */
  /* package */ @Nested
  class IndexRulesTest {

    @Test
    @DisplayName("K7：btree以外のインデックスを拒否する")
    void rejectsNonBtreeIndexes() {
      assertThat(keys(SchemaTableConventions.indexes(List.of(index(ITEM, "hash", false, 1, 1)))))
          .containsExactly("K7 item.idx_1_m_item");
    }

    @Test
    @DisplayName("K7：排他制約のGiSTだけを受け入れる")
    void acceptsGistOnlyForExclusionConstraints() {
      final IndexRow exclusion =
          new IndexRow(
              ITEM, TABLE, "ex_m_item", "gist", false, 2, 2, false, false, true, false, null);
      final IndexRow plainGist =
          new IndexRow(
              ITEM, TABLE, "idx_2_m_item", "gist", false, 1, 1, false, false, false, false, null);

      assertThat(keys(SchemaTableConventions.indexes(List.of(exclusion, plainGist))))
          .containsExactly("K7 item.idx_2_m_item");
    }

    @Test
    @DisplayName("K8：式インデックスを拒否する")
    void rejectsExpressionIndexes() {
      final IndexRow row =
          new IndexRow(
              ITEM, TABLE, "idx_1_m_item", BTREE, false, 1, 1, true, false, false, false, null);

      assertThat(keys(SchemaTableConventions.indexes(List.of(row))))
          .containsExactly("K8 item.idx_1_m_item");
    }

    @Test
    @DisplayName("K9：部分インデックスとINCLUDE付きインデックスを拒否する")
    void rejectsPartialAndIncludeIndexes() {
      final IndexRow partial =
          new IndexRow(
              ITEM, TABLE, "idx_2_m_item", BTREE, false, 1, 1, false, true, false, false, null);
      final IndexRow include = index(ITEM, BTREE, false, 1, 2);

      assertThat(keys(SchemaTableConventions.indexes(List.of(partial, include))))
          .as("部分インデックス: %s、INCLUDE: %s", partial, include)
          .containsExactlyInAnyOrder("K9 item.idx_2_m_item", "K9 item.idx_1_m_item");
    }

    @Test
    @DisplayName("P1：複合主キーを拒否する")
    void rejectsCompositePrimaryKeys() {
      assertThat(keys(SchemaTableConventions.indexes(List.of(index(ITEM, BTREE, true, 2, 2)))))
          .containsExactly("P1 item.idx_1_m_item");
    }

    @Test
    @DisplayName("P2：主キーのないテーブルを拒否し、ビューは判定しない")
    void rejectsTablesWithoutPrimaryKey() {
      final List<RelationRow> rows =
          List.of(relation(ITEM, "r", false, null), relation(ITEM, "v", false, null));

      assertThat(keys(SchemaTableConventions.primaryKeys(rows)))
          .as("テーブルとビュー: %s", rows)
          .containsExactly("P2 item.m_item");
    }

    @Test
    @DisplayName("modulithとliquibaseのインデックスとテーブルを判定しない")
    void ignoresFrameworkSchemas() {
      assertThat(
              SchemaTableConventions.indexes(
                  List.of(
                      index(MODULITH, "hash", true, 2, 3), index(LIQUIBASE, "gin", false, 1, 1))))
          .isEmpty();
      assertThat(
              SchemaTableConventions.primaryKeys(
                  List.of(
                      relation(MODULITH, "r", false, null), relation(LIQUIBASE, "r", false, null))))
          .isEmpty();
    }
  }

  /** カラムと型。 */
  /* package */ @Nested
  class ColumnRulesTest {

    @Test
    @DisplayName("T1：serialとbigserial（nextvalの既定値）を拒否する")
    void rejectsSerialColumns() {
      final ColumnRow row =
          column(ITEM, BIGINT, "b", "", "", "nextval('item.m_item_seq'::regclass)");

      assertThat(keys(SchemaTableConventions.columns(List.of(row))))
          .containsExactly("T1 item.m_item.item_id");
    }

    @Test
    @DisplayName("T2：BY DEFAULTのIDENTITYを拒否する")
    void rejectsIdentityByDefault() {
      assertThat(
              keys(
                  SchemaTableConventions.columns(
                      List.of(column(ITEM, BIGINT, "b", "d", "", null)))))
          .containsExactly("T2 item.m_item.item_id");
    }

    @Test
    @DisplayName("T2：ALWAYSのIDENTITYはbigintだけを受け入れる")
    void acceptsIdentityAlwaysOnlyForBigint() {
      assertThat(SchemaTableConventions.columns(List.of(column(ITEM, BIGINT, "b", "a", "", null))))
          .as(BIGINT)
          .isEmpty();
      assertThat(
              keys(
                  SchemaTableConventions.columns(
                      List.of(column(ITEM, INTEGER, "b", "a", "", null)))))
          .as(INTEGER)
          .containsExactly("T2 item.m_item.item_id");
    }

    @Test
    @DisplayName("T11：生成列を拒否する")
    void rejectsGeneratedColumns() {
      assertThat(
              keys(
                  SchemaTableConventions.columns(
                      List.of(column(ITEM, BIGINT, "b", "", "s", null)))))
          .containsExactly("T11 item.m_item.item_id");
    }

    @Test
    @DisplayName("T10：oidのカラムとラージオブジェクトの存在を拒否する")
    void rejectsLargeObjects() {
      assertThat(
              keys(
                  SchemaConventions.largeObjects(
                      List.of(column(ITEM, "oid", "b", "", "", null)), 0)))
          .as("oidのカラム")
          .containsExactly("T10 item.m_item.item_id");
      assertThat(keys(SchemaConventions.largeObjects(List.of(), 1)))
          .as("pg_largeobject_metadataが1件")
          .containsExactly("T10 pg_largeobject_metadata");
    }

    @Test
    @DisplayName("T12：DOMAINとENUMの定義と使用を拒否する")
    void rejectsDomainsAndEnums() {
      final List<TypeRow> types = List.of(new TypeRow(ITEM, "item_status", "e"));
      final List<ColumnRow> columns = List.of(column(ITEM, "item.code", "d", "", "", null));

      assertThat(keys(SchemaConventions.userDefinedTypes(types, columns)))
          .as("型: %s、カラム: %s", types, columns)
          .containsExactlyInAnyOrder("T12 item.item_status", "T12 item.m_item.item_id");
    }

    @Test
    @DisplayName("modulithとliquibaseのカラムと型を判定しない")
    void ignoresFrameworkSchemas() {
      final List<ColumnRow> columns =
          List.of(
              column(MODULITH, "oid", "d", "d", "s", "nextval('x'::regclass)"),
              column(LIQUIBASE, INTEGER, "e", "a", "s", null));

      assertThat(SchemaTableConventions.columns(columns)).as("カラム").isEmpty();
      assertThat(SchemaConventions.largeObjects(columns, 0)).as("ラージオブジェクト").isEmpty();
      assertThat(
              SchemaConventions.userDefinedTypes(
                  List.of(new TypeRow(MODULITH, "x", "d"), new TypeRow(LIQUIBASE, "y", "e")),
                  columns))
          .as("独自型")
          .isEmpty();
    }
  }

  /** uuid カラムの DEFAULT。 */
  /* package */ @Nested
  class UuidDefaultRuleTest {

    @Test
    @DisplayName("T13：uuidのDEFAULTにuuidv7()を使うと拒否する")
    void rejectsUuidDefaultedByUuidv7() {
      assertThat(
              keys(
                  SchemaTableConventions.columns(
                      List.of(column(ITEM, UUID, "b", "", "", "uuidv7()")))))
          .containsExactly("T13 item.m_item.item_id");
    }

    @Test
    @DisplayName("T13：uuidのDEFAULTにgen_random_uuid()を使うと拒否する")
    void rejectsUuidDefaultedByGenRandomUuid() {
      assertThat(
              keys(
                  SchemaTableConventions.columns(
                      List.of(column(ITEM, UUID, "b", "", "", "gen_random_uuid()")))))
          .containsExactly("T13 item.m_item.item_id");
    }

    @Test
    @DisplayName("T13：DEFAULTのないuuidを許可する")
    void acceptsUuidWithoutDefault() {
      assertThat(
              keys(SchemaTableConventions.columns(List.of(column(ITEM, UUID, "b", "", "", null)))))
          .isEmpty();
    }

    @Test
    @DisplayName("T13：uuid以外のDEFAULTを許可する")
    void acceptsNonUuidColumnWithDefault() {
      assertThat(
              keys(
                  SchemaTableConventions.columns(
                      List.of(
                          column(
                              ITEM,
                              "character varying(10)",
                              "b",
                              "",
                              "",
                              "''::character varying")))))
          .isEmpty();
    }

    @Test
    @DisplayName("T13：テナントの列の、トランザクションのテナントを入れるDEFAULTだけを許可する")
    void acceptsOnlyTenantColumnDefaultedByCurrentTenant() {
      final String tenantDefault = TenantIsolation.CATALOG_TENANT_DEFAULT;

      assertThat(
              keys(
                  SchemaTableConventions.columns(
                      List.of(
                          new ColumnRow(
                              ITEM, TABLE, "tenant_public_id", UUID, "b", "", "", tenantDefault),
                          new ColumnRow(ITEM, TABLE, "public_id", UUID, "b", "", "", tenantDefault),
                          new ColumnRow(
                              ITEM,
                              TABLE,
                              "tenant_public_id",
                              UUID,
                              "b",
                              "",
                              "",
                              "gen_random_uuid()")))))
          .containsExactlyInAnyOrder(
              "T13 item.m_item.public_id", "T13 item.m_item.tenant_public_id");
    }

    @Test
    @DisplayName("T13：イベント出版テーブルのuuidはDEFAULTの有無を問わず対象外にする")
    void ignoresEventPublicationUuidColumns() {
      assertThat(
              SchemaTableConventions.columns(
                  List.of(
                      new ColumnRow(
                          MODULITH,
                          "event_publication",
                          "id",
                          "uuid",
                          "b",
                          "",
                          "",
                          "gen_random_uuid()"),
                      new ColumnRow(
                          MODULITH, "event_publication_archive", "id", UUID, "b", "", "", null))))
          .isEmpty();
    }
  }

  /** 主キーと外に出す ID。 */
  /* package */ @Nested
  class PublicIdRulesTest {

    @Test
    @DisplayName("P3：別のモジュールの行を指す列は、uuidの<対象>_public_idだけを受け入れる")
    void rejectsReferencesToOtherModulesExceptUuidPublicIds() {
      final List<ColumnRow> columns =
          List.of(
              column(ITEM, ITEM_ID, BIGINT),
              column(ITEM, "public_id", UUID),
              column(ITEM, "order_public_id", UUID),
              column(ITEM, "created_tx_id", "character varying(32)"),
              column(ITEM, "patched_id", INTEGER),
              column(ITEM, "item_code", "character varying(20)"),
              column(ITEM, "order_id", BIGINT),
              column(ITEM, "customer_id", UUID),
              column(ITEM, "product_public_id", BIGINT),
              column(ITEM, "legacy_public_id", "character varying(36)"));

      assertThat(
              keys(
                  SchemaTableConventions.references(
                      List.of(relation(ITEM, "r", true, null)), columns)))
          .as("カラム: %s", columns)
          .containsExactlyInAnyOrder(
              "P3 item.m_item.order_id",
              "P3 item.m_item.customer_id",
              "P3 item.m_item.product_public_id",
              "P3 item.m_item.legacy_public_id");
    }

    @Test
    @DisplayName("P3：履歴の表の archived_tx_id は、行を指さない列として受け入れる")
    void acceptsArchivedTraceId() {
      assertThat(
              SchemaTableConventions.references(
                  List.of(relation(ITEM, "r", true, null)),
                  List.of(column(ITEM, "archived_tx_id", "character varying(32)"))))
          .isEmpty();
    }

    @Test
    @DisplayName("P3：別のスキーマの主キーと同じ名前の列を、同じモジュールの参照とみなさない")
    void rejectsColumnsNamedAfterAnotherSchemasPrimaryKey() {
      assertThat(
              keys(
                  SchemaTableConventions.references(
                      List.of(relation("other", "r", true, null)),
                      List.of(column(ITEM, ITEM_ID, BIGINT)))))
          .containsExactly("P3 item.m_item.item_id");
    }

    @Test
    @DisplayName("P4：スキーマをまたぐ外部キーを拒否し、同じスキーマの外部キーは判定しない")
    void rejectsCrossSchemaForeignKeys() {
      final List<ConstraintRow> rows =
          List.of(
              new ConstraintRow(ITEM, TABLE, "fk_m_item_order", "f", "ordering"),
              new ConstraintRow(ITEM, TABLE, "fk_m_item_parent", "f", ITEM),
              new ConstraintRow(ITEM, TABLE, "pk_m_item", "p", null));

      assertThat(keys(SchemaTableConventions.crossSchemaForeignKeys(rows)))
          .as("制約: %s", rows)
          .containsExactly("P4 item.fk_m_item_order");
    }

    @Test
    @DisplayName("modulithとliquibaseの列と外部キーを判定しない")
    void ignoresFrameworkSchemas() {
      assertThat(
              SchemaTableConventions.references(
                  List.of(), List.of(column(MODULITH, "listener_id", BIGINT))))
          .as("列")
          .isEmpty();
      assertThat(
              SchemaTableConventions.crossSchemaForeignKeys(
                  List.of(new ConstraintRow(LIQUIBASE, TABLE, "fk_x", "f", ITEM))))
          .as("外部キー")
          .isEmpty();
    }
  }

  /** 関数、ビュー、継承、拡張機能。 */
  /* package */ @Nested
  class ObjectRulesTest {

    @Test
    @DisplayName("O1：業務スキーマの関数を拒否する")
    void rejectsFunctions() {
      assertThat(
              keys(SchemaConventions.functions(List.of(new FunctionRow(ITEM, "item.fn(bigint)")))))
          .containsExactly("O1 item.item.fn(bigint)");
    }

    @Test
    @DisplayName("O3：入れ子のビューを拒否する")
    void rejectsNestedViews() {
      final List<ViewDependencyRow> rows =
          List.of(new ViewDependencyRow(ITEM, "v_m_item_2", ITEM, "v_m_item"));

      assertThat(keys(SchemaConventions.viewDependencies(rows)))
          .containsExactly("O3 item.v_m_item_2");
    }

    @Test
    @DisplayName("O4：パーティションでないテーブル継承を拒否する")
    void rejectsInheritance() {
      assertThat(
              keys(SchemaConventions.inheritance(List.of(relation(ITEM, "r", true, "m_parent")))))
          .containsExactly("O4 item.m_item");
    }

    @Test
    @DisplayName("O7：許可していない拡張機能を拒否し、plpgsqlを受け入れる")
    void rejectsUnpermittedExtensions() {
      assertThat(keys(SchemaConventions.extensions(List.of("plpgsql", "dblink", "postgres_fdw"))))
          .containsExactlyInAnyOrder("O7 dblink", "O7 postgres_fdw");
    }

    @Test
    @DisplayName("modulithとliquibaseの関数、ビュー、継承を判定しない")
    void ignoresFrameworkSchemas() {
      assertThat(
              SchemaConventions.functions(
                  List.of(new FunctionRow(MODULITH, "f()"), new FunctionRow(LIQUIBASE, "g()"))))
          .as("関数")
          .isEmpty();
      assertThat(
              SchemaConventions.viewDependencies(
                  List.of(new ViewDependencyRow(MODULITH, "v_a", MODULITH, "v_b"))))
          .as("ビュー")
          .isEmpty();
      assertThat(SchemaConventions.inheritance(List.of(relation(LIQUIBASE, "r", true, "p"))))
          .as("継承")
          .isEmpty();
    }
  }

  /** ロールの権限。 */
  /* package */ @Nested
  class PrivilegeRulesTest {

    @Test
    @DisplayName("G1：ロールのDDLにつながる属性を拒否する")
    void rejectsPowerfulRoleAttributes() {
      final RoleRow role = new RoleRow(ROLE, true, true, true, true, true, true);

      assertThat(keys(SchemaPrivilegeConventions.appRole(role, List.of(), List.of())))
          .containsExactlyInAnyOrder(
              "G1 demo:SUPERUSER",
              "G1 demo:CREATEDB",
              "G1 demo:CREATEROLE",
              "G1 demo:REPLICATION",
              "G1 demo:BYPASSRLS",
              "G1 demo:CREATE:DATABASE");
    }

    @Test
    @DisplayName("G1：スキーマのCREATEと所有を拒否する")
    void rejectsSchemaCreateAndOwnership() {
      final List<SchemaPrivilegeRow> schemas =
          List.of(new SchemaPrivilegeRow(ITEM, true, true, true));

      assertThat(keys(SchemaPrivilegeConventions.appRole(cleanRole(), schemas, List.of())))
          .containsExactlyInAnyOrder(
              "G1 demo:CREATE:item", "G1 demo:OWNER:item", "G1 demo:USAGE:item");
    }

    @Test
    @DisplayName("G1：modulith以外のスキーマのUSAGEを拒否し、modulithとPostgreSQLの既定のpublicのUSAGEを受け入れる")
    void rejectsUsageOnSchemasOtherThanModulith() {
      final List<SchemaPrivilegeRow> schemas =
          List.of(
              new SchemaPrivilegeRow(MODULITH, true, false, false),
              new SchemaPrivilegeRow("public", true, false, false),
              new SchemaPrivilegeRow(ITEM, true, false, false),
              new SchemaPrivilegeRow(LIQUIBASE, true, false, false));

      assertThat(keys(SchemaPrivilegeConventions.appRole(cleanRole(), schemas, List.of())))
          .containsExactlyInAnyOrder("G1 demo:USAGE:item", "G1 demo:USAGE:liquibase");
    }

    @Test
    @DisplayName("G1：modulith以外のテーブルへの権限とDML以外のテーブルの権限を拒否する")
    void rejectsBusinessRelationsAndNonDmlPrivileges() {
      final List<RelationPrivilegeRow> relations =
          List.of(
              relationPrivilege(LIQUIBASE, true, false, false),
              relationPrivilege(ITEM, true, true, true),
              new RelationPrivilegeRow(ITEM, "m_other", true, false, true, true, true, false));

      assertThat(keys(SchemaPrivilegeConventions.appRole(cleanRole(), List.of(), relations)))
          .containsExactlyInAnyOrder(
              "G1 demo:ANY:liquibase.m_item",
              "G1 demo:ANY:item.m_item",
              "G1 demo:ANY:item.m_other",
              "G1 demo:TRUNCATE:item.m_item",
              "G1 demo:OWNER:item.m_item",
              "G1 demo:REFERENCES:item.m_other",
              "G1 demo:TRIGGER:item.m_other",
              "G1 demo:MAINTAIN:item.m_other");
    }

    @Test
    @DisplayName("G2：マイグレーションロールのsuperuserとBYPASSRLSを拒否する")
    void rejectsSuperuserAndBypassRlsOnMigrationRole() {
      final RoleRow role = new RoleRow(MIGRATION_ROLE, true, false, false, false, true, true);

      assertThat(keys(SchemaPrivilegeConventions.migrationRole(role)))
          .containsExactlyInAnyOrder("G2 demo_migration:SUPERUSER", "G2 demo_migration:BYPASSRLS");
    }

    @Test
    @DisplayName("G2：表を作れるマイグレーションロールを受け入れる")
    void acceptsMigrationRoleThatCanCreate() {
      final RoleRow role = new RoleRow(MIGRATION_ROLE, false, false, false, false, false, true);

      assertThat(SchemaPrivilegeConventions.migrationRole(role)).isEmpty();
    }
  }

  /** モジュールのロールの形と権限。 */
  /* package */ @Nested
  class ModuleRoleRulesTest {

    /** 機能モジュールの直接の依存先。ordering は product を読む。 */
    private final Map<String, Set<String>> dependencies =
        Map.of(PRODUCT, Set.of(), "ordering", Set.of(PRODUCT));

    /** 業務のテーブルとイベントの出版の表。 */
    private final List<RelationRow> relations =
        List.of(
            new RelationRow(PRODUCT, "m_product", "r", null, null),
            new RelationRow("ordering", "t_order", "r", null, null),
            new RelationRow("modulith", "event_publication", "r", null, null));

    @Test
    @DisplayName("G4：ログインできる、DDLの属性を持つ、アプリロールが継承するモジュールのロールを拒否する")
    void rejectsPowerfulModuleRoles() {
      final ModuleRoleRow bad =
          new ModuleRoleRow(
              new RoleRow(PRODUCT_ROLE, true, true, true, true, true, true),
              true,
              false,
              true,
              Set.of());

      assertThat(keys(ModulePrivilegeConventions.roles(List.of(bad), Set.of(PRODUCT))))
          .containsExactlyInAnyOrder(
              "G4 product_module:SUPERUSER",
              "G4 product_module:CREATEDB",
              "G4 product_module:CREATEROLE",
              "G4 product_module:REPLICATION",
              "G4 product_module:BYPASSRLS",
              "G4 product_module:CREATE:DATABASE",
              "G4 product_module:LOGIN",
              "G4 product_module:APP_ROLE_CANNOT_SET",
              "G4 product_module:APP_ROLE_INHERITS");
    }

    @Test
    @DisplayName("G4：モジュールに対応するロールがない、ロールに対応するモジュールがないことを拒否する")
    void rejectsMissingAndUnexpectedModuleRoles() {
      final ModuleRoleRow stray = moduleRole("stray_module", Set.of());

      assertThat(keys(ModulePrivilegeConventions.roles(List.of(stray), Set.of(PRODUCT))))
          .containsExactlyInAnyOrder("G4 product_module:MISSING", "G4 stray_module:UNEXPECTED");
    }

    @Test
    @DisplayName("G4：切り替えだけができるログインできないロールを受け入れる")
    void acceptsSwitchOnlyRole() {
      assertThat(
              ModulePrivilegeConventions.roles(
                  List.of(moduleRole(PRODUCT_ROLE, Set.of())), Set.of(PRODUCT)))
          .isEmpty();
    }

    @Test
    @DisplayName("G5：自分のスキーマのDML、依存先のSELECT、出版へのINSERTだけを受け入れる")
    void acceptsExpectedPrivileges() {
      final ModuleRoleRow product =
          moduleRole(
              PRODUCT_ROLE,
              Set.of(
                  "USAGE:product",
                  "SELECT:product.m_product",
                  "INSERT:product.m_product",
                  "UPDATE:product.m_product",
                  "DELETE:product.m_product",
                  "USAGE:modulith",
                  "INSERT:modulith.event_publication"));

      assertThat(ModulePrivilegeConventions.privileges(List.of(product), dependencies, relations))
          .isEmpty();
    }

    @Test
    @DisplayName("G5：依存の方向に逆らう読み取りと、余計な権限と、足りない権限を拒否する")
    void rejectsUnexpectedAndMissingPrivileges() {
      final ModuleRoleRow product =
          moduleRole(
              PRODUCT_ROLE,
              Set.of(
                  "USAGE:product",
                  "SELECT:product.m_product",
                  "INSERT:product.m_product",
                  "UPDATE:product.m_product",
                  "USAGE:ordering",
                  "SELECT:ordering.t_order",
                  "TRUNCATE:product.m_product",
                  "USAGE:modulith",
                  "INSERT:modulith.event_publication"));

      assertThat(
              keys(
                  ModulePrivilegeConventions.privileges(List.of(product), dependencies, relations)))
          .containsExactlyInAnyOrder(
              "G5 product_module:unexpected:USAGE:ordering",
              "G5 product_module:unexpected:SELECT:ordering.t_order",
              "G5 product_module:unexpected:TRUNCATE:product.m_product",
              "G5 product_module:missing:DELETE:product.m_product");
    }

    @Test
    @DisplayName("G5：依存先のスキーマのSELECTが足りないことを拒否する")
    void rejectsMissingDependencyPrivileges() {
      final ModuleRoleRow ordering =
          moduleRole(
              "ordering_module",
              Set.of(
                  "USAGE:ordering",
                  "SELECT:ordering.t_order",
                  "INSERT:ordering.t_order",
                  "UPDATE:ordering.t_order",
                  "DELETE:ordering.t_order",
                  "USAGE:modulith",
                  "INSERT:modulith.event_publication"));

      assertThat(
              keys(
                  ModulePrivilegeConventions.privileges(
                      List.of(ordering), dependencies, relations)))
          .containsExactlyInAnyOrder(
              "G5 ordering_module:missing:USAGE:product",
              "G5 ordering_module:missing:SELECT:product.m_product");
    }

    private ModuleRoleRow moduleRole(final String name, final Set<String> privileges) {
      return new ModuleRoleRow(
          new RoleRow(name, false, false, false, false, false, false),
          false,
          true,
          false,
          privileges);
    }
  }

  private static RelationRow relation(
      final String schema, final String relkind, final boolean hasPrimaryKey, final String parent) {
    return new RelationRow(schema, TABLE, relkind, hasPrimaryKey ? ITEM_ID : null, parent);
  }

  private static ColumnRow column(final String schema, final String name, final String typeName) {
    return new ColumnRow(schema, TABLE, name, typeName, "b", "", "", null);
  }

  private static ColumnRow column(
      final String schema,
      final String typeName,
      final String typeKind,
      final String identity,
      final String generated,
      final String defaultExpression) {
    return new ColumnRow(
        schema, TABLE, ITEM_ID, typeName, typeKind, identity, generated, defaultExpression);
  }

  private static RoleRow cleanRole() {
    return new RoleRow(ROLE, false, false, false, false, false, false);
  }

  private static RelationPrivilegeRow relationPrivilege(
      final String schema,
      final boolean anyPrivilege,
      final boolean truncate,
      final boolean owned) {
    return new RelationPrivilegeRow(
        schema, TABLE, anyPrivilege, truncate, false, false, false, owned);
  }
}
