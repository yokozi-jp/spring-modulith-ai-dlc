package com.example.demo.persistence;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.ordering.domain.model.Money;
import com.example.demo.ordering.domain.model.ProductId;
import com.example.demo.ordering.domain.model.ProductOffer;
import com.example.demo.ordering.infrastructure.persistence.OrderSeeds;
import com.example.demo.payment.infrastructure.persistence.PaymentSeeds;
import com.example.demo.product.ProductSeeds;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import com.example.demo.shared.tenant.TenantContext;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.DatabaseTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import net.datafaker.Faker;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 業務の表の RLS が、別のテナントの行の読み書きと、テナントを設定し忘れた読み書きを DB で止めることを確かめる（ADR-107）。
 *
 * <p>テストのトランザクションは {@link AsTenant#TENANT} のテナントで動く。別のテナントの商品、注文、明細、決済は、そのテナントを束縛した {@link
 * AsModule} で入れる。業務の表は、{@code tenant_public_id} を持つ生成した表から導く。
 */
// 生成したテーブルと AssertJ を static import で読みやすくする。
@SuppressWarnings("PMD.TooManyStaticImports")
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

  /** テスト用の DB の jOOQ のコンテキスト。テストごとにロールバックする。 */
  @Autowired private DSLContext dsl;

  /** 名前のあるトランザクションを、本番と同じトランザクションマネージャーで始める。 */
  @Autowired private PlatformTransactionManager transactionManager;

  /** テナントの列を持つ、生成した業務の表。 */
  private static List<Table<?>> businessTables() {
    return DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
        .flatMap(schema -> schema.getTables().stream())
        .filter(table -> table.field(TENANT_COLUMN) != null)
        .toList();
  }

  /** 別のテナントに、商品、注文と明細、決済を入れる。 */
  private void seedOtherTenant() {
    final Random random = new Random(377L);
    final Faker faker = new Faker(Locale.ENGLISH, random);
    final List<ProductOffer> offers =
        asOther(PRODUCT_MODULE, tx -> ProductSeeds.insert(tx, faker, UUID::randomUUID, SEEDED_AT))
            .stream()
            .filter(ProductSeeds.Seeded::onSale)
            .map(seeded ->
                new ProductOffer(
                    new ProductId(seeded.publicId()), new Money(seeded.unitPrice()), true))
            .toList();
    final OrderSeeds.Confirmed confirmed =
        asOther(ORDERING_MODULE, tx -> OrderSeeds.insert(tx, offers, UUID::randomUUID, SEEDED_AT));
    asOther(
        "payment",
        tx -> {
          PaymentSeeds.insert(
              tx,
              UUID.randomUUID(),
              confirmed.paid().id().value(),
              confirmed.paid().total().amount(),
              false,
              SEEDED_AT);
          return null;
        });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("businessTables")
  @DisplayName("別のテナントの行は、そのテナントからは見え、テストのテナントからは見えない")
  void otherTenantRowsAreInvisible(final Table<?> table) {
    seedOtherTenant();
    final Field<UUID> tenant = tenantOf(table);

    assertThat(
            this.<Integer>asOther(
                AsModule.moduleOf(table), tx -> tx.fetchCount(table, tenant.eq(OTHER.value()))))
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
  @MethodSource("businessTables")
  @DisplayName("別のテナントの行は、テストのテナントから更新も削除もできない")
  void otherTenantRowsAreNotWritable(final Table<?> table) {
    seedOtherTenant();
    final Field<UUID> tenant = tenantOf(table);
    final Field<String> updatedBy =
        Objects.requireNonNull(table.field("updated_by", String.class), "updated_by");
    final String module = AsModule.moduleOf(table);
    final int before =
        this.<Integer>asOther(module, tx -> tx.fetchCount(table, tenant.eq(OTHER.value())));

    final int updated =
        AsModule.<Integer>call(
            dsl,
            module,
            tx ->
                tx.update(table).set(updatedBy, UPDATER).where(tenant.eq(OTHER.value())).execute());
    final int deleted =
        AsModule.<Integer>call(
            dsl, module, tx -> tx.deleteFrom(table).where(tenant.eq(OTHER.value())).execute());

    assertThat(List.of(updated, deleted)).as("%s の更新と削除の件数", table).containsOnly(0);
    assertThat(
            this.<Integer>asOther(
                module,
                tx -> tx.fetchCount(table, tenant.eq(OTHER.value()).and(updatedBy.ne(UPDATER)))))
        .as("%s の別のテナントの行", table)
        .isEqualTo(before);
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
  @DisplayName("明細は、別のテナントの注文を指せず、複合の外部キーで拒否される")
  void orderLineCannotReferToAnotherTenantsOrder() {
    seedOtherTenant();
    final Long otherOrder =
        asOther(
            ORDERING_MODULE,
            tx -> tx.select(T_ORDER.ORDER_ID).from(T_ORDER).limit(1).fetchSingle(T_ORDER.ORDER_ID));

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("businessTables")
  // テナントを設定しないトランザクションを、テストのトランザクションの外で本番のトランザクションマネージャーから始める。
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("テナントを設定し忘れたトランザクションは、業務の表を 0 件で読まずにエラーになる")
  void readingWithoutTenantFails(final Table<?> table) {
    final TransactionTemplate module = new TransactionTemplate(transactionManager);
    // ModuleRoles はトランザクションの名前のベースパッケージの直下からモジュールを引く。
    module.setName("com.example.demo." + AsModule.moduleOf(table) + ".TenantProbe.read");

    assertThat(TenantContext.findCurrent()).as("テストのスレッドのテナントの束縛").isEmpty();
    assertSqlState(() -> module.execute(status -> dsl.fetchCount(table)), UNSET, EMPTY);
  }

  /** 別のテナントを束縛し、モジュールのロールで本体を実行する。 */
  private <T> T asOther(final String module, final Function<DSLContext, T> body) {
    return TenantContext.call(OTHER, () -> AsModule.call(dsl, module, body));
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
