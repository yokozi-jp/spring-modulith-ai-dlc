package com.example.demo.persistence.conventions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.persistence.conventions.CatalogSnapshot.ColumnRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ConstraintRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.FunctionRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.IndexRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RelationRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.RoleRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.SchemaPrivilegeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TriggerRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.TypeRow;
import com.example.demo.persistence.conventions.CatalogSnapshot.ViewDependencyRow;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * スキーマ検査の純粋な判定（{@link SchemaConventions}、{@link SchemaTableConventions}、{@link
 * SchemaPrivilegeConventions}）を、違反を含む行のデータで検証する。
 *
 * <p>DBに接続しない。各テストは違反の規則の記号と対象を、{@code 規則 対象}のキーの集合で確かめる。
 */
class SchemaConventionsTest {

  /** 業務スキーマ。 */
  private static final String ITEM = "item";

  /** Spring Modulithのスキーマ。 */
  private static final String MODULITH = "modulith";

  /** Liquibaseの管理テーブルのスキーマ。 */
  private static final String LIQUIBASE = "liquibase";

  /** 業務テーブル。 */
  private static final String TABLE = "m_item";

  /** uuidの型名。 */
  private static final String UUID = "uuid";

  /** bigintの型名。 */
  private static final String BIGINT = "bigint";

  /** integerの型名。 */
  private static final String INTEGER = "integer";

  /** btreeのアクセスメソッド。 */
  private static final String BTREE = "btree";

  /** トリガー名。 */
  private static final String TRIGGER = "trg";

  /** アプリロール。 */
  private static final String ROLE = "demo";

  @Test
  @DisplayName("規約を満たす行だけなら違反がない")
  void conformingSnapshotHasNoViolations() {
    final CatalogSnapshot snapshot =
        new CatalogSnapshot(
            List.of(relation(ITEM, "r", true, null), relation(MODULITH, "r", false, null)),
            List.of(
                column(ITEM, BIGINT, "b", "a", "", null),
                column(MODULITH, INTEGER, "b", "", "", "nextval('x'::regclass)")),
            List.of(
                new ConstraintRow(ITEM, TABLE, "pk_m_item", "p"),
                new ConstraintRow(ITEM, TABLE, "m_item_item_code_not_null", "n")),
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
            List.of(
                new SchemaPrivilegeRow(ITEM, true, false, false),
                new SchemaPrivilegeRow(MODULITH, true, false, false),
                new SchemaPrivilegeRow(LIQUIBASE, false, false, false)),
            List.of(
                relationPrivilege(ITEM, true, false, false),
                relationPrivilege(LIQUIBASE, false, false, false)));

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
    @DisplayName("K1、K2、K3：外部キー、CHECK、ユニークの制約を拒否する")
    void rejectsForeignKeyCheckAndUniqueConstraints() {
      final List<ConstraintRow> rows =
          List.of(
              new ConstraintRow(ITEM, TABLE, "fk_x", "f"),
              new ConstraintRow(ITEM, TABLE, "chk_x", "c"),
              new ConstraintRow(ITEM, TABLE, "uq_x", "u"));

      assertThat(keys(SchemaTableConventions.constraints(rows)))
          .as("制約: %s", rows)
          .containsExactlyInAnyOrder("K1 item.fk_x", "K2 item.chk_x", "K3 item.uq_x");
    }

    @Test
    @DisplayName("K2：PG18のNOT NULL制約（contype='n'）をCHECKとみなさない")
    void ignoresNotNullConstraints() {
      assertThat(
              SchemaTableConventions.constraints(
                  List.of(new ConstraintRow(ITEM, TABLE, "m_item_x_not_null", "n"))))
          .isEmpty();
    }

    @Test
    @DisplayName("modulithとliquibaseのトリガーと制約を判定しない")
    void ignoresFrameworkSchemas() {
      assertThat(
              SchemaTableConventions.constraints(
                  List.of(
                      new ConstraintRow(MODULITH, TABLE, "fk_x", "f"),
                      new ConstraintRow(LIQUIBASE, TABLE, "chk_x", "c"))))
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
          new IndexRow(ITEM, TABLE, "ex_m_item", "gist", false, 2, 2, false, false, true);
      final IndexRow plainGist =
          new IndexRow(ITEM, TABLE, "idx_2_m_item", "gist", false, 1, 1, false, false, false);

      assertThat(keys(SchemaTableConventions.indexes(List.of(exclusion, plainGist))))
          .containsExactly("K7 item.idx_2_m_item");
    }

    @Test
    @DisplayName("K8：式インデックスを拒否する")
    void rejectsExpressionIndexes() {
      final IndexRow row =
          new IndexRow(ITEM, TABLE, "idx_1_m_item", BTREE, false, 1, 1, true, false, false);

      assertThat(keys(SchemaTableConventions.indexes(List.of(row))))
          .containsExactly("K8 item.idx_1_m_item");
    }

    @Test
    @DisplayName("K9：部分インデックスとINCLUDE付きインデックスを拒否する")
    void rejectsPartialAndIncludeIndexes() {
      final IndexRow partial =
          new IndexRow(ITEM, TABLE, "idx_2_m_item", BTREE, false, 1, 1, false, true, false);
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
  }

  /** uuidカラムのDEFAULT。 */
  /* package */ @Nested
  class UuidDefaultRulesTest {

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

  /** アプリロールの権限。 */
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
          .containsExactlyInAnyOrder("G1 demo:CREATE:item", "G1 demo:OWNER:item");
    }

    @Test
    @DisplayName("G1：liquibaseのUSAGEを拒否し、modulithのUSAGEを受け入れる")
    void rejectsUsageOnLiquibaseOnly() {
      final List<SchemaPrivilegeRow> schemas =
          List.of(
              new SchemaPrivilegeRow(MODULITH, true, false, false),
              new SchemaPrivilegeRow(LIQUIBASE, true, false, false));

      assertThat(keys(SchemaPrivilegeConventions.appRole(cleanRole(), schemas, List.of())))
          .containsExactly("G1 demo:USAGE:liquibase");
    }

    @Test
    @DisplayName("G1：liquibaseのテーブルへの権限とDML以外のテーブルの権限を拒否する")
    void rejectsLiquibaseRelationsAndNonDmlPrivileges() {
      final List<RelationPrivilegeRow> relations =
          List.of(
              relationPrivilege(LIQUIBASE, true, false, false),
              relationPrivilege(ITEM, true, true, true),
              new RelationPrivilegeRow(ITEM, "m_other", true, false, true, true, true, false));

      assertThat(keys(SchemaPrivilegeConventions.appRole(cleanRole(), List.of(), relations)))
          .containsExactlyInAnyOrder(
              "G1 demo:ANY:liquibase.m_item",
              "G1 demo:TRUNCATE:item.m_item",
              "G1 demo:OWNER:item.m_item",
              "G1 demo:REFERENCES:item.m_other",
              "G1 demo:TRIGGER:item.m_other",
              "G1 demo:MAINTAIN:item.m_other");
    }
  }

  private static Set<String> keys(final List<ConventionViolation> violations) {
    return violations.stream().map(ConventionViolation::key).collect(Collectors.toSet());
  }

  private static RelationRow relation(
      final String schema, final String relkind, final boolean hasPrimaryKey, final String parent) {
    return new RelationRow(schema, TABLE, relkind, hasPrimaryKey, parent);
  }

  private static ColumnRow column(
      final String schema,
      final String typeName,
      final String typeKind,
      final String identity,
      final String generated,
      final String defaultExpression) {
    return new ColumnRow(
        schema, TABLE, "item_id", typeName, typeKind, identity, generated, defaultExpression);
  }

  private static IndexRow index(
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
        false);
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
