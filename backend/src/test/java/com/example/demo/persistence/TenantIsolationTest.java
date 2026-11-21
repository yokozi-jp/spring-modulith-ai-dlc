package com.example.demo.persistence;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager;
import com.example.demo.shared.infrastructure.persistence.ModuleRoles;
import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.BusinessSeeds;
import com.example.demo.testkit.DatabaseTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import net.datafaker.Faker;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 業務の表の RLS が、別のテナントの行の読み書きと、テナントを設定し忘れた読み書きを DB で止めることと、汎用の実行計画では DB の防御だけに頼れないことを確かめる（ADR-107）。
 */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.TooManyMethods"})
@DatabaseTest
class TenantIsolationTest {

  /** 商品のモジュール。 */
  private static final String PRODUCT_MODULE = "product";

  /** 注文のモジュール。 */
  private static final String ORDERING_MODULE = "ordering";

  /** 業務の表のテナントの列。 */
  private static final String TENANT_COLUMN = "tenant_public_id";

  /** 別のテナントの行の更新を見分ける、更新者の値。 */
  private static final String UPDATER = "tenant-isolation-test";

  /** テストの利用者のテナントでない、別のテナント。 */
  private static final TenantId OTHER =
      new TenantId(UUID.fromString("0f4b7c2a-6d1e-4a8b-9c3d-5e6f7a8b9c0d"));

  /** 別のテナントの行の作成と更新の時刻。 */
  private static final Instant SEEDED_AT = Instant.parse("2026-10-01T00:00:00Z");

  /** RLS の WITH CHECK を満たさない行の SQLSTATE（insufficient_privilege）。 */
  private static final String POLICY_VIOLATION = "42501";

  /** 外部キーの違反の SQLSTATE（foreign_key_violation）。 */
  private static final String FOREIGN_KEY_VIOLATION = "23503";

  /** テナントを一度も設定していない接続の SQLSTATE（undefined_object）。 */
  private static final String UNSET = "42704";

  /** 前のトランザクションの設定が空文字列で残った接続の SQLSTATE（invalid_text_representation）。 */
  private static final String EMPTY = "22P02";

  /** 業務の表を返す引数の供給元。入れ子のテストクラスからも引く。 */
  private static final String BUSINESS_TABLES =
      "com.example.demo.persistence.TenantIsolationTest#businessTables";

  /** プールの接続に汎用の計画を残すために、同じ問い合わせを流す回数。 */
  private static final int GENERIC_PLAN_ROUNDS = 8;

  /** テスト用の DB の jOOQ のコンテキスト。テストごとにロールバックする。 */
  @Autowired private DSLContext dsl;

  /** 表の所有者の接続先と資格情報。 */
  @Autowired private Environment environment;

  /* package */ static List<Table<?>> businessTables() {
    return DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
        .flatMap(schema -> schema.getTables().stream())
        .filter(table -> table.field(TENANT_COLUMN) != null)
        .toList();
  }

  /** 別のテナントの商品、注文、明細、決済を入れてから確かめる、読み書きと外部キー。 */
  /* package */ @Nested
  class OtherTenantRowsTest {

    @BeforeEach
    void seedOtherTenant() {
      BusinessSeeds.insert(
          dsl, OTHER, new Faker(Locale.ENGLISH, new Random(377L)), UUID::randomUUID, SEEDED_AT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(BUSINESS_TABLES)
    @DisplayName("別のテナントの行は、そのテナントからは見え、テストのテナントからは見えない")
    void otherTenantRowsAreInvisible(final Table<?> table) {
      final Field<UUID> tenant = tenantOf(table);

      assertThat(
              AsModule.<Integer>call(
                  dsl,
                  AsModule.moduleOf(table),
                  OTHER,
                  tx -> tx.fetchCount(table, tenant.eq(OTHER.value()))))
          .as("%s の別のテナントの行を、別のテナントから数える", table)
          .isPositive();
      assertThat(
              AsModule.<Integer>call(
                  dsl,
                  AsModule.moduleOf(table),
                  tx -> tx.fetchCount(table, tenant.eq(OTHER.value()))))
          .as("%s の別のテナントの行を、テストのテナントから数える", table)
          .isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(BUSINESS_TABLES)
    @DisplayName("別のテナントの行は、テストのテナントから更新も削除もできない")
    void otherTenantRowsAreNotWritable(final Table<?> table) {
      final Field<UUID> tenant = tenantOf(table);
      final Field<String> updatedBy =
          Objects.requireNonNull(table.field("updated_by", String.class), "updated_by");
      final String module = AsModule.moduleOf(table);
      final int before =
          AsModule.<Integer>call(
              dsl, module, OTHER, tx -> tx.fetchCount(table, tenant.eq(OTHER.value())));

      final int updated =
          AsModule.<Integer>call(
              dsl,
              module,
              tx ->
                  tx.update(table)
                      .set(updatedBy, UPDATER)
                      .where(tenant.eq(OTHER.value()))
                      .execute());
      final int deleted =
          AsModule.<Integer>call(
              dsl, module, tx -> tx.deleteFrom(table).where(tenant.eq(OTHER.value())).execute());

      assertThat(List.of(updated, deleted)).as("%s の更新と削除の件数", table).containsOnly(0);
      assertThat(
              AsModule.<Integer>call(
                  dsl,
                  module,
                  OTHER,
                  tx -> tx.fetchCount(table, tenant.eq(OTHER.value()).and(updatedBy.ne(UPDATER)))))
          .as("%s の別のテナントの行", table)
          .isEqualTo(before);
    }

    @Test
    @DisplayName("明細は、別のテナントの注文を指せず、複合の外部キーで拒否される")
    void orderLineCannotReferToAnotherTenantsOrder() {
      final Long otherOrder =
          AsModule.call(
              dsl,
              ORDERING_MODULE,
              OTHER,
              tx ->
                  tx.select(T_ORDER.ORDER_ID).from(T_ORDER).limit(1).fetchSingle(T_ORDER.ORDER_ID));

      assertSqlState(
          () ->
              AsModule.run(
                  dsl,
                  ORDERING_MODULE,
                  tx ->
                      TestCommonColumns.runAs(() ->
                          tx.insertInto(T_ORDER_LINE)
                              .set(T_ORDER_LINE.ORDER_ID, otherOrder)
                              .set(T_ORDER_LINE.LINE_NO, 99)
                              .set(T_ORDER_LINE.PRODUCT_PUBLIC_ID, UUID.randomUUID())
                              .set(T_ORDER_LINE.ORDERED_COUNT, 1)
                              .set(T_ORDER_LINE.ORDERED_UNIT_PRICE_JPY, BigDecimal.TEN)
                              .set(TestCommonColumns.at(SEEDED_AT).forInsert(T_ORDER_LINE))
                              .execute())),
          FOREIGN_KEY_VIOLATION);
    }
  }

  @Test
  @DisplayName("別のテナントの値の行は、INSERT も UPDATE も RLS の WITH CHECK で拒否される")
  void rowsOfAnotherTenantCannotBeWritten() {
    final UUID own = TestProducts.onSale(dsl, "TENANT-OWN", "100");

    assertSqlState(
        () ->
            AsModule.run(
                dsl,
                PRODUCT_MODULE,
                tx ->
                    TestCommonColumns.runAs(() ->
                        tx.insertInto(M_PRODUCT)
                            .set(M_PRODUCT.TENANT_PUBLIC_ID, OTHER.value())
                            .set(M_PRODUCT.PUBLIC_ID, UUID.randomUUID())
                            .set(M_PRODUCT.PRODUCT_CODE, "TENANT-OTHER")
                            .set(M_PRODUCT.PRODUCT_NAME, "other")
                            .set(M_PRODUCT.UNIT_PRICE_JPY, BigDecimal.TEN)
                            .set(M_PRODUCT.SALES_STATUS_TYP, "ON_SALE")
                            .set(TestCommonColumns.at(SEEDED_AT).forInsert(M_PRODUCT))
                            .execute())),
        POLICY_VIOLATION);
    assertSqlState(
        () ->
            AsModule.run(
                dsl,
                PRODUCT_MODULE,
                tx ->
                    tx.update(M_PRODUCT)
                        .set(M_PRODUCT.TENANT_PUBLIC_ID, OTHER.value())
                        .where(M_PRODUCT.PUBLIC_ID.eq(own))
                        .execute()),
        POLICY_VIOLATION);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("汎用の実行計画が残った接続では、テナントを設定しない読み取りがエラーにならず 0 件を返しうるので、DB の防御だけには頼れない")
  void genericPlanCanReturnNoRowsWithoutTenant() {
    final Function<DSLContext, Integer> countUnknownStatus =
        tx -> {
          // 索引で RLS の式を引く計画は走査の初めにエラーになるため、順に走査する汎用の計画にする。
          tx.execute(
              "select set_config('enable_indexscan', 'off', true),"
                  + " set_config('enable_bitmapscan', 'off', true),"
                  + " set_config('plan_cache_mode', 'force_generic_plan', true)");
          return tx.fetchCount(T_ORDER, T_ORDER.ORDER_STATUS_TYP.eq("NO_SUCH_STATUS"));
        };
    // テナントのある間に、プールの接続へ汎用の計画を残す。pgjdbc は 5 回目からサーバーの prepared statement にする。
    IntStream.range(0, GENERIC_PLAN_ROUNDS)
        .forEach(round -> AsModule.call(dsl, ORDERING_MODULE, countUnknownStatus));

    // 残った計画は、漏洩のない比較で全行を落とし、RLS の式を評価しないまま 0 件を返す。
    assertThat(withoutTenant(ORDERING_MODULE, countUnknownStatus)).as("テナントを設定しない読み取りの件数").isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource(BUSINESS_TABLES)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("テナントを設定しないモジュールのロールは、業務の表を 0 件で読まずにエラーになる")
  void readingWithoutTenantFails(final Table<?> table) {
    assertSqlState(
        () -> withoutTenant(AsModule.moduleOf(table), tx -> tx.fetchCount(table)), UNSET, EMPTY);
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("テナントを設定しないモジュールのロールの INSERT は、行を入れずにエラーになる")
  void insertingWithoutTenantFails() {
    assertSqlState(
        () ->
            withoutTenant(PRODUCT_MODULE, tx -> tx.insertInto(M_PRODUCT).defaultValues().execute()),
        UNSET,
        EMPTY);
  }

  @Test
  @DisplayName("表の所有者のマイグレーションロールも、テナントを設定しないと業務の表を読めない")
  void ownerCannotReadWithoutTenant() throws SQLException {
    try (Connection connection = ownerConnection()) {
      final DSLContext owner = DSL.using(connection, SQLDialect.POSTGRES);

      assertSqlState(() -> owner.fetchCount(M_PRODUCT), UNSET);
      connection.setAutoCommit(false);
      ModuleRoleTransactionManager.setLocal(connection, null, OTHER.toString());
      connection.commit();
      assertSqlState(() -> owner.fetchCount(M_PRODUCT), EMPTY);
    }
  }

  @Test
  @DisplayName("表の所有者のマイグレーションロールは、設定したテナントの行だけを読む")
  void ownerReadsOnlyTheTenantItSets() throws SQLException {
    try (Connection connection = ownerConnection()) {
      connection.setAutoCommit(false);
      final DSLContext owner = DSL.using(connection, SQLDialect.POSTGRES);
      try {
        for (final TenantId tenant : List.of(AsTenant.TENANT, OTHER)) {
          ModuleRoleTransactionManager.setLocal(connection, null, tenant.toString());
          TestCommonColumns.runAs(() ->
              TestProducts.insert(
                  owner,
                  UUID.randomUUID(),
                  "OWNER-" + tenant.value().toString().substring(0, 8),
                  "owner",
                  BigDecimal.TEN,
                  "ON_SALE",
                  SEEDED_AT));
        }
        ModuleRoleTransactionManager.setLocal(connection, null, OTHER.toString());

        assertThat(
                owner
                    .select(M_PRODUCT.TENANT_PUBLIC_ID)
                    .from(M_PRODUCT)
                    .where(M_PRODUCT.PRODUCT_CODE.startsWith("OWNER-"))
                    .fetch(M_PRODUCT.TENANT_PUBLIC_ID))
            .as("所有者が別のテナントを設定して読んだ商品のテナント")
            .containsExactly(OTHER.value());
      } finally {
        connection.rollback();
      }
    }
  }

  private Connection ownerConnection() throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", environment.getRequiredProperty("MIGRATION_DB_USERNAME"));
    properties.setProperty("password", environment.getRequiredProperty("MIGRATION_DB_PASSWORD"));
    return DriverManager.getConnection(
        environment.getRequiredProperty("spring.datasource.url"), properties);
  }

  /** トランザクションマネージャーの検査を通らずに、DB の防御だけを確かめる。 */
  private <T> T withoutTenant(final String module, final Function<DSLContext, T> body) {
    return dsl.transactionResult(configuration -> {
      final DSLContext tx = configuration.dsl();
      tx.connection(connection ->
          ModuleRoleTransactionManager.setLocal(connection, ModuleRoles.roleOf(module), null));
      return body.apply(tx);
    });
  }

  private static void assertSqlState(final Runnable action, final String... expected) {
    final Throwable thrown = catchThrowable(action::run);

    assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
        .as("例外: %s", thrown)
        .isInstanceOfSatisfying(
            SQLException.class, cause -> assertThat(cause.getSQLState()).isIn((Object[]) expected));
  }

  private static Field<UUID> tenantOf(final Table<?> table) {
    return Objects.requireNonNull(table.field(TENANT_COLUMN, UUID.class), TENANT_COLUMN);
  }
}
