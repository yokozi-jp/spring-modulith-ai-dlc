package com.example.demo;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_TENANT;
import static com.example.demo.jooq.tenancy.Tables.M_USER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.LocalDataSeeder.Mode;
import com.example.demo.LocalDataSeeder.Outcome;
import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TenancySeeds;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.DatabaseTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 開発用の代表データのシーダーの、件数、再実行、reset、ID の形を確かめる。 */
@DatabaseTest
// 生成したテーブルと AssertJ を static import で読みやすくする。
// 読み取りの補助メソッドが増えるため、メソッド数の上限も外す。
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
class LocalDataSeederTest {

  /** test-user と test-admin を所属させる発行者。 */
  private static final String ISSUER = "http://127.0.0.1:8081/realms/spring-modulith";

  /** 固定の時計のシーダー。main と同じ基準の時刻にする。 */
  private static final LocalDataSeeder SEEDER =
      new LocalDataSeeder(
          Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC), ISSUER);

  /** 商品のモジュール。 */
  private static final String PRODUCT = "product";

  /** 注文のモジュール。 */
  private static final String ORDERING = "ordering";

  /** テナントと所属のモジュール。 */
  private static final String TENANCY = "tenancy";

  /** 決済のモジュール。決済のロールは、依存の方向に沿って注文のスキーマも読める。 */
  private static final String PAYMENT = "payment";

  /** 代表データを入れる 2 つのテナント。 */
  private static final List<TenantId> TENANTS = TenancySeeds.TENANTS;

  /** test-user と test-admin が属するテナント。 */
  private static final TenantId TRADING = new TenantId(TenancySeeds.TRADING_TENANT);

  /** テスト用の DB の jOOQ のコンテキスト。テストごとにロールバックする。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName(
      "空の DB の 2 つのテナントのそれぞれに、BUSINESS_TABLES のすべての表の行と、商品 5 件、注文 6 件、決済 2 件を入れ、2 回目は何も入れない")
  void seedsRepresentativeDataOnlyWhenEmpty() {
    for (final TenantId tenant : TENANTS) {
      LocalDataSeeder.BUSINESS_TABLES.forEach(table ->
          inModule(tenant, AsModule.moduleOf(table), tx -> tx.deleteFrom(table).execute()));
    }
    final int publications = dsl.fetchCount(EVENT_PUBLICATION);

    assertThat(SEEDER.seed(dsl, Mode.SEED)).isEqualTo(Outcome.SEEDED);

    for (final TenantId tenant : TENANTS) {
      assertRepresentativeData(tenant);
      for (final Table<?> table : LocalDataSeeder.BUSINESS_TABLES) {
        assertThat(
                this.<Integer>inModule(
                    tenant, AsModule.moduleOf(table), tx -> tx.fetchCount(table)))
            .as("テナント %s の %s の行数", tenant, table)
            .isPositive();
      }
    }
    assertThat(dsl.fetchCount(EVENT_PUBLICATION)).isEqualTo(publications);

    final List<List<List<Map<String, Object>>>> before = snapshot();
    assertThat(SEEDER.seed(dsl, Mode.SEED)).isEqualTo(Outcome.SKIPPED_NOT_EMPTY);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  @DisplayName("業務データがあっても、2 つのテナントと test-user と test-admin の所属を入れ、何度入れても増やさない")
  void seedsTenantsAndMembershipsIdempotently() {
    TestProducts.onSale(dsl, TRADING, "NOT-SEED-2", "100");

    assertThat(SEEDER.seed(dsl, Mode.SEED)).isEqualTo(Outcome.SKIPPED_NOT_EMPTY);
    final Map<String, List<String>> first = memberships();
    SEEDER.seed(dsl, Mode.SEED);

    assertThat(first)
        .isEqualTo(
            Map.of(
                TenancySeeds.TEST_USER_SUBJECT,
                List.of("サンプル商事:MEMBER", "サンプル物産:MEMBER"),
                TenancySeeds.TEST_ADMIN_SUBJECT,
                List.of("サンプル商事:ADMIN")));
    assertThat(memberships()).as("2 回目の seed の後の所属").isEqualTo(first);
  }

  @Test
  @DisplayName("reset は業務データだけを消して同じ代表データを入れ直し、出版の行を残す")
  void resetReplacesOnlyBusinessDataWithSameValues() {
    final UUID publication = UUID.fromString("11111111-1111-4111-8111-111111111111");
    final Instant publishedAt = Instant.parse("2026-09-30T00:00:00Z");
    dsl.insertInto(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.ID, publication)
        .set(EVENT_PUBLICATION.LISTENER_ID, "seeder-test")
        .set(EVENT_PUBLICATION.EVENT_TYPE, "test.event")
        .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{}")
        .set(EVENT_PUBLICATION.PUBLICATION_DATE, publishedAt)
        .execute();
    dsl.insertInto(EVENT_PUBLICATION_ARCHIVE)
        .set(EVENT_PUBLICATION_ARCHIVE.ID, publication)
        .set(EVENT_PUBLICATION_ARCHIVE.LISTENER_ID, "seeder-test")
        .set(EVENT_PUBLICATION_ARCHIVE.EVENT_TYPE, "test.event")
        .set(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT, "{}")
        .set(EVENT_PUBLICATION_ARCHIVE.PUBLICATION_DATE, publishedAt)
        .execute();
    TestProducts.onSale(dsl, TRADING, "NOT-SEED-1", "100");

    assertThat(SEEDER.seed(dsl, Mode.RESET)).isEqualTo(Outcome.SEEDED);
    final List<List<List<Map<String, Object>>>> first = snapshot();
    assertThat(SEEDER.seed(dsl, Mode.RESET)).isEqualTo(Outcome.SEEDED);

    assertThat(snapshot()).isEqualTo(first);
    assertThat(
            this.<Boolean>inModule(
                TRADING,
                PRODUCT,
                tx -> tx.fetchExists(M_PRODUCT, M_PRODUCT.PRODUCT_CODE.eq("NOT-SEED-1"))))
        .isFalse();
    assertThat(dsl.fetchExists(EVENT_PUBLICATION, EVENT_PUBLICATION.ID.eq(publication))).isTrue();
    assertThat(
            dsl.fetchExists(
                EVENT_PUBLICATION_ARCHIVE, EVENT_PUBLICATION_ARCHIVE.ID.eq(publication)))
        .isTrue();
  }

  @Test
  @DisplayName("消す対象の表は、modulith と tenancy 以外の全スキーマの表と一致する")
  void businessTablesCoverAllBusinessTables() {
    final Set<Table<?>> generated = new HashSet<>();
    // tenancy の利用者は、ログインで作る行で代表データではない。消すと、ログイン中のセッションの利用者 ID が指す行がなくなる。
    DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
        .filter(schema -> !Set.of("modulith", "tenancy").contains(schema.getName()))
        .forEach(schema -> generated.addAll(schema.getTables()));

    assertThat(Set.copyOf(LocalDataSeeder.BUSINESS_TABLES)).isEqualTo(generated);
  }

  @Test
  @DisplayName("入れた public_id は UUID v4 の形である")
  void publicIdsAreVersion4() {
    SEEDER.seed(dsl, Mode.RESET);

    final List<UUID> ids = TENANTS.stream().flatMap(tenant -> publicIds(tenant).stream()).toList();

    assertThat(ids)
        .isNotEmpty()
        .doesNotHaveDuplicates()
        .allSatisfy(id -> assertThat(List.of(id.version(), id.variant())).isEqualTo(List.of(4, 2)));
  }

  private List<UUID> publicIds(final TenantId tenant) {
    return Stream.of(
            inModule(
                tenant,
                PRODUCT,
                tx -> tx.select(M_PRODUCT.PUBLIC_ID).from(M_PRODUCT).fetch(M_PRODUCT.PUBLIC_ID)),
            inModule(
                tenant,
                ORDERING,
                tx -> tx.select(T_ORDER.PUBLIC_ID).from(T_ORDER).fetch(T_ORDER.PUBLIC_ID)),
            inModule(
                tenant,
                PAYMENT,
                tx -> tx.select(T_PAYMENT.PUBLIC_ID).from(T_PAYMENT).fetch(T_PAYMENT.PUBLIC_ID)))
        .flatMap(List::stream)
        .toList();
  }

  private void assertRepresentativeData(final TenantId tenant) {

    assertThat(this.<Integer>inModule(tenant, PRODUCT, tx -> tx.fetchCount(M_PRODUCT)))
        .isEqualTo(5);
    assertThat(
            this.<List<String>>inModule(
                tenant,
                PRODUCT,
                tx ->
                    tx.select(M_PRODUCT.PRODUCT_CODE)
                        .from(M_PRODUCT)
                        .where(M_PRODUCT.SALES_STATUS_TYP.eq("DISCONTINUED"))
                        .fetch(M_PRODUCT.PRODUCT_CODE)))
        .containsExactly("SEED-P05");
    assertThat(
            this.<Map<String, Integer>>inModule(
                tenant,
                ORDERING,
                tx ->
                    tx.select(T_ORDER.ORDER_STATUS_TYP, DSL.count())
                        .from(T_ORDER)
                        .groupBy(T_ORDER.ORDER_STATUS_TYP)
                        .fetchMap(T_ORDER.ORDER_STATUS_TYP, DSL.count())))
        .isEqualTo(Map.of("DRAFT", 2, "CONFIRMED", 3, "CANCELLED", 1));
    assertThat(
            this.<Integer>inModule(
                tenant,
                ORDERING,
                tx ->
                    tx.fetchCount(
                        T_ORDER,
                        DSL.notExists(
                            tx.selectOne()
                                .from(T_ORDER_LINE)
                                .where(T_ORDER_LINE.ORDER_ID.eq(T_ORDER.ORDER_ID))))))
        .isZero();
    assertThat(
            this.<Map<String, String>>inModule(
                tenant,
                PAYMENT,
                tx ->
                    tx.select(
                            T_ORDER.CUSTOMER_ORDER_CODE,
                            DSL.coalesce(T_PAYMENT.PAYMENT_STATUS_TYP, DSL.inline("NONE")))
                        .from(T_ORDER)
                        .leftJoin(T_PAYMENT)
                        .on(T_PAYMENT.ORDER_PUBLIC_ID.eq(T_ORDER.PUBLIC_ID))
                        .where(T_ORDER.ORDER_STATUS_TYP.eq("CONFIRMED"))
                        .fetchMap(Record2::value1, Record2::value2)))
        .isEqualTo(Map.of("SEED-C03", "PAID", "SEED-C05", "DECLINED", "SEED-C06", "NONE"));
    final Result<Record3<String, BigDecimal, BigDecimal>> amounts =
        inModule(
            tenant,
            PAYMENT,
            tx ->
                tx.select(
                        T_PAYMENT.GATEWAY_PAYMENT_CODE,
                        T_PAYMENT.CHARGED_AMOUNT_JPY,
                        DSL.sum(
                            T_ORDER_LINE.ORDERED_UNIT_PRICE_JPY.mul(T_ORDER_LINE.ORDERED_COUNT)))
                    .from(T_PAYMENT)
                    .join(T_ORDER)
                    .on(T_ORDER.PUBLIC_ID.eq(T_PAYMENT.ORDER_PUBLIC_ID))
                    .join(T_ORDER_LINE)
                    .on(T_ORDER_LINE.ORDER_ID.eq(T_ORDER.ORDER_ID))
                    .groupBy(T_PAYMENT.GATEWAY_PAYMENT_CODE, T_PAYMENT.CHARGED_AMOUNT_JPY)
                    .fetch());
    assertThat(amounts).hasSize(2);
    assertThat(amounts)
        .allSatisfy(row -> assertThat(row.value2()).isEqualByComparingTo(row.value3()));
    assertThat(createdPgmCds(tenant, M_PRODUCT, M_PRODUCT.CREATED_PGM_CD, M_PRODUCT.CREATED_BY))
        .containsOnly("product.SeedLocalData");
    assertThat(createdPgmCds(tenant, T_ORDER, T_ORDER.CREATED_PGM_CD, T_ORDER.CREATED_BY))
        .containsOnly("ordering.SeedLocalData");
    assertThat(
            createdPgmCds(
                tenant, T_ORDER_LINE, T_ORDER_LINE.CREATED_PGM_CD, T_ORDER_LINE.CREATED_BY))
        .containsOnly("ordering.SeedLocalData");
    assertThat(createdPgmCds(tenant, T_PAYMENT, T_PAYMENT.CREATED_PGM_CD, T_PAYMENT.CREATED_BY))
        .containsOnly("payment.SeedLocalData");
  }

  private <T> T inModule(
      final TenantId tenant, final String module, final Function<DSLContext, T> body) {
    return AsModule.call(dsl, module, tenant, body);
  }

  private Set<String> createdPgmCds(
      final TenantId tenant,
      final Table<?> table,
      final Field<String> pgmCd,
      final Field<String> createdBy) {
    return inModule(
        tenant,
        AsModule.moduleOf(table),
        tx -> {
          final Set<String> values =
              new HashSet<>(tx.selectDistinct(pgmCd).from(table).fetch(pgmCd));
          values.addAll(tx.selectDistinct(createdBy).from(table).fetch(createdBy));
          return values;
        });
  }

  /** 発行者 {@link #ISSUER} の subject ごとの、「テナントの名前:役割」の一覧。 */
  private Map<String, List<String>> memberships() {
    return AsModule.call(
        dsl,
        TENANCY,
        tx ->
            tx.select(
                    M_USER_IDENTITY.SUBJECT_CODE,
                    DSL.arrayAgg(
                            DSL.concat(
                                M_TENANT.TENANT_NAME, DSL.inline(":"), M_MEMBERSHIP.ROLE_TYP))
                        .orderBy(M_TENANT.TENANT_NAME))
                .from(M_MEMBERSHIP)
                .join(M_USER_IDENTITY)
                .on(M_USER_IDENTITY.USER_ID.eq(M_MEMBERSHIP.USER_ID))
                .join(M_TENANT)
                .on(M_TENANT.TENANT_ID.eq(M_MEMBERSHIP.TENANT_ID))
                .where(M_USER_IDENTITY.ISSUER_URL.eq(ISSUER))
                .groupBy(M_USER_IDENTITY.SUBJECT_CODE)
                .fetchMap(Record2::value1, row -> List.of(row.value2())));
  }

  private List<List<List<Map<String, Object>>>> snapshot() {
    return TENANTS.stream().map(this::tenantSnapshot).toList();
  }

  private List<List<Map<String, Object>>> tenantSnapshot(final TenantId tenant) {
    return List.of(
        rows(tenant, M_PRODUCT, M_PRODUCT.PRODUCT_ID, M_PRODUCT.PUBLIC_ID),
        rows(tenant, T_ORDER, T_ORDER.ORDER_ID, T_ORDER.PUBLIC_ID),
        rows(tenant, T_PAYMENT, T_PAYMENT.PAYMENT_ID, T_PAYMENT.PUBLIC_ID),
        inModule(
            tenant,
            ORDERING,
            tx ->
                tx.select(T_ORDER.PUBLIC_ID)
                    .select(
                        without(T_ORDER_LINE, T_ORDER_LINE.ORDER_LINE_ID, T_ORDER_LINE.ORDER_ID))
                    .from(T_ORDER_LINE)
                    .join(T_ORDER)
                    .on(T_ORDER.ORDER_ID.eq(T_ORDER_LINE.ORDER_ID))
                    .orderBy(T_ORDER.PUBLIC_ID, T_ORDER_LINE.LINE_NO)
                    .fetch()
                    .intoMaps()));
  }

  private List<Map<String, Object>> rows(
      final TenantId tenant,
      final Table<?> table,
      final Field<Long> identity,
      final Field<UUID> publicId) {
    return inModule(
        tenant,
        AsModule.moduleOf(table),
        tx -> tx.select(without(table, identity)).from(table).orderBy(publicId).fetch().intoMaps());
  }

  private static List<Field<?>> without(final Table<?> table, final Field<?>... excluded) {
    final List<Field<?>> excludedFields = List.of(excluded);
    return Stream.of(table.fields()).filter(field -> !excludedFields.contains(field)).toList();
  }
}
