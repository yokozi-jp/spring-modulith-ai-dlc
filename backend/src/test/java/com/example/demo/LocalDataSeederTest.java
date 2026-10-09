package com.example.demo;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.LocalDataSeeder.Mode;
import com.example.demo.LocalDataSeeder.Outcome;
import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.product.TestProducts;
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

/** 開発用の代表データのシーダーの、件数、再実行、reset、環境の判定、ID の形を確かめる。 */
@DatabaseTest
// 生成したテーブルと AssertJ を static import で読みやすくする。
@SuppressWarnings("PMD.TooManyStaticImports")
class LocalDataSeederTest {

  /** 固定の時計のシーダー。main と同じ基準の時刻にする。 */
  private static final LocalDataSeeder SEEDER =
      new LocalDataSeeder(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));

  /** テスト用の DB の jOOQ のコンテキスト。テストごとにロールバックする。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("空の DB に商品 5 件、注文 6 件、決済 2 件を入れ、2 回目は何も入れない")
  void seedsRepresentativeDataOnlyWhenEmpty() {
    LocalDataSeeder.BUSINESS_TABLES.forEach(table -> dsl.deleteFrom(table).execute());
    final int publications = dsl.fetchCount(EVENT_PUBLICATION);

    assertThat(SEEDER.seed(dsl, Mode.SEED)).isEqualTo(Outcome.SEEDED);

    assertThat(dsl.fetchCount(M_PRODUCT)).isEqualTo(5);
    assertThat(
            dsl.select(M_PRODUCT.PRODUCT_CODE)
                .from(M_PRODUCT)
                .where(M_PRODUCT.SALES_STATUS_TYP.eq("DISCONTINUED"))
                .fetch(M_PRODUCT.PRODUCT_CODE))
        .containsExactly("SEED-P05");
    assertThat(
            dsl.select(T_ORDER.ORDER_STATUS_TYP, DSL.count())
                .from(T_ORDER)
                .groupBy(T_ORDER.ORDER_STATUS_TYP)
                .fetchMap(T_ORDER.ORDER_STATUS_TYP, DSL.count()))
        .isEqualTo(Map.of("DRAFT", 2, "CONFIRMED", 3, "CANCELLED", 1));
    assertThat(
            dsl.fetchCount(
                T_ORDER,
                DSL.notExists(
                    dsl.selectOne()
                        .from(T_ORDER_LINE)
                        .where(T_ORDER_LINE.ORDER_ID.eq(T_ORDER.ORDER_ID)))))
        .isZero();
    assertThat(
            dsl.select(
                    T_ORDER.CUSTOMER_ORDER_CODE,
                    DSL.coalesce(T_PAYMENT.PAYMENT_STATUS_TYP, DSL.inline("NONE")))
                .from(T_ORDER)
                .leftJoin(T_PAYMENT)
                .on(T_PAYMENT.ORDER_PUBLIC_ID.eq(T_ORDER.PUBLIC_ID))
                .where(T_ORDER.ORDER_STATUS_TYP.eq("CONFIRMED"))
                .fetchMap(Record2::value1, Record2::value2))
        .isEqualTo(Map.of("SEED-C03", "PAID", "SEED-C05", "DECLINED", "SEED-C06", "NONE"));
    final Result<Record3<String, BigDecimal, BigDecimal>> amounts =
        dsl.select(
                T_PAYMENT.GATEWAY_PAYMENT_CODE,
                T_PAYMENT.CHARGED_AMOUNT_JPY,
                DSL.sum(T_ORDER_LINE.ORDERED_UNIT_PRICE_JPY.mul(T_ORDER_LINE.ORDERED_COUNT)))
            .from(T_PAYMENT)
            .join(T_ORDER)
            .on(T_ORDER.PUBLIC_ID.eq(T_PAYMENT.ORDER_PUBLIC_ID))
            .join(T_ORDER_LINE)
            .on(T_ORDER_LINE.ORDER_ID.eq(T_ORDER.ORDER_ID))
            .groupBy(T_PAYMENT.GATEWAY_PAYMENT_CODE, T_PAYMENT.CHARGED_AMOUNT_JPY)
            .fetch();
    assertThat(amounts).hasSize(2);
    assertThat(amounts)
        .allSatisfy(row -> assertThat(row.value2()).isEqualByComparingTo(row.value3()));
    assertThat(createdPgmCds(M_PRODUCT, M_PRODUCT.CREATED_PGM_CD, M_PRODUCT.CREATED_BY))
        .containsOnly("product.SeedLocalData");
    assertThat(createdPgmCds(T_ORDER, T_ORDER.CREATED_PGM_CD, T_ORDER.CREATED_BY))
        .containsOnly("ordering.SeedLocalData");
    assertThat(createdPgmCds(T_ORDER_LINE, T_ORDER_LINE.CREATED_PGM_CD, T_ORDER_LINE.CREATED_BY))
        .containsOnly("ordering.SeedLocalData");
    assertThat(createdPgmCds(T_PAYMENT, T_PAYMENT.CREATED_PGM_CD, T_PAYMENT.CREATED_BY))
        .containsOnly("payment.SeedLocalData");
    assertThat(dsl.fetchCount(EVENT_PUBLICATION)).isEqualTo(publications);

    final List<List<Map<String, Object>>> before = snapshot();
    assertThat(SEEDER.seed(dsl, Mode.SEED)).isEqualTo(Outcome.SKIPPED_NOT_EMPTY);
    assertThat(snapshot()).isEqualTo(before);
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
    TestProducts.onSale(dsl, "NOT-SEED-1", "100");

    assertThat(SEEDER.seed(dsl, Mode.RESET)).isEqualTo(Outcome.SEEDED);
    final List<List<Map<String, Object>>> first = snapshot();
    assertThat(SEEDER.seed(dsl, Mode.RESET)).isEqualTo(Outcome.SEEDED);

    assertThat(snapshot()).isEqualTo(first);
    assertThat(dsl.fetchExists(M_PRODUCT, M_PRODUCT.PRODUCT_CODE.eq("NOT-SEED-1"))).isFalse();
    assertThat(dsl.fetchExists(EVENT_PUBLICATION, EVENT_PUBLICATION.ID.eq(publication))).isTrue();
    assertThat(
            dsl.fetchExists(
                EVENT_PUBLICATION_ARCHIVE, EVENT_PUBLICATION_ARCHIVE.ID.eq(publication)))
        .isTrue();
  }

  @Test
  @DisplayName("目印が local か test で DB_HOST が loopback のときだけ、ローカルの環境とみなす")
  // loopback の判定を確かめるテストのデータであり、接続先の設定ではない。
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  void acceptsOnlyLocalEnvironment() {
    assertThat(LocalDataSeeder.isLocalEnvironment(env("local", "localhost"))).isTrue();
    assertThat(LocalDataSeeder.isLocalEnvironment(env("test", "127.0.0.1"))).isTrue();
    for (final Map<String, String> rejected :
        List.of(
            env("stg", "localhost"),
            env("local", "db.example.com"),
            Map.of("DB_HOST", "localhost"))) {
      assertThat(LocalDataSeeder.isLocalEnvironment(rejected)).isFalse();
      assertThatThrownBy(() -> LocalDataSeeder.requireLocalEnvironment(rejected))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("消す対象の表は、modulith 以外の全スキーマの表と一致する")
  void businessTablesCoverAllNonModulithTables() {
    final Set<Table<?>> generated = new HashSet<>();
    DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
        .filter(schema -> !"modulith".equals(schema.getName()))
        .forEach(schema -> generated.addAll(schema.getTables()));

    assertThat(Set.copyOf(LocalDataSeeder.BUSINESS_TABLES)).isEqualTo(generated);
  }

  @Test
  @DisplayName("入れた public_id は UUID v4 の形である")
  void publicIdsAreVersion4() {
    SEEDER.seed(dsl, Mode.RESET);

    final List<UUID> ids =
        Stream.of(
                dsl.select(M_PRODUCT.PUBLIC_ID).from(M_PRODUCT).fetch(M_PRODUCT.PUBLIC_ID),
                dsl.select(T_ORDER.PUBLIC_ID).from(T_ORDER).fetch(T_ORDER.PUBLIC_ID),
                dsl.select(T_PAYMENT.PUBLIC_ID).from(T_PAYMENT).fetch(T_PAYMENT.PUBLIC_ID))
            .flatMap(List::stream)
            .toList();

    assertThat(ids).hasSize(13);
    assertThat(ids)
        .allSatisfy(id -> assertThat(List.of(id.version(), id.variant())).isEqualTo(List.of(4, 2)));
  }

  private Set<String> createdPgmCds(
      final Table<?> table, final Field<String> pgmCd, final Field<String> createdBy) {
    final Set<String> values = new HashSet<>(dsl.selectDistinct(pgmCd).from(table).fetch(pgmCd));
    values.addAll(dsl.selectDistinct(createdBy).from(table).fetch(createdBy));
    return values;
  }

  /** reset の後に比べる値。IDENTITY の主キーとそれを指す列を除き、public_id（明細は注文の public_id と番号）で並べる。 */
  private List<List<Map<String, Object>>> snapshot() {
    return List.of(
        rows(M_PRODUCT, M_PRODUCT.PRODUCT_ID, M_PRODUCT.PUBLIC_ID),
        rows(T_ORDER, T_ORDER.ORDER_ID, T_ORDER.PUBLIC_ID),
        rows(T_PAYMENT, T_PAYMENT.PAYMENT_ID, T_PAYMENT.PUBLIC_ID),
        dsl.select(T_ORDER.PUBLIC_ID)
            .select(without(T_ORDER_LINE, T_ORDER_LINE.ORDER_LINE_ID, T_ORDER_LINE.ORDER_ID))
            .from(T_ORDER_LINE)
            .join(T_ORDER)
            .on(T_ORDER.ORDER_ID.eq(T_ORDER_LINE.ORDER_ID))
            .orderBy(T_ORDER.PUBLIC_ID, T_ORDER_LINE.LINE_NO)
            .fetch()
            .intoMaps());
  }

  private List<Map<String, Object>> rows(
      final Table<?> table, final Field<Long> identity, final Field<UUID> publicId) {
    return dsl.select(without(table, identity)).from(table).orderBy(publicId).fetch().intoMaps();
  }

  private static List<Field<?>> without(final Table<?> table, final Field<?>... excluded) {
    final List<Field<?>> excludedFields = List.of(excluded);
    return Stream.of(table.fields()).filter(field -> !excludedFields.contains(field)).toList();
  }

  private static Map<String, String> env(final String environment, final String host) {
    return Map.of("OTEL_DEPLOYMENT_ENVIRONMENT_NAME", environment, "DB_HOST", host);
  }
}
