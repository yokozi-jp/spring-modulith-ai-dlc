package com.example.demo.product;

import static com.example.demo.jooq.product.Tables.M_PRODUCT;

import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/** 商品の行をテストで登録する補助。ステップ 1 には商品を作る操作がないため、DSLContext で直接 INSERT する。 */
// テストの補助であり、テストケースを持たない。
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestProducts {

  /** 共通カラムに登録する時刻。 */
  private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T00:00:00Z");

  private TestProducts() {}

  /** 販売中の商品を登録し、商品 ID を返す。 */
  public static UUID onSale(
      final DSLContext dsl, final String productCode, final String unitPrice) {
    return insert(dsl, productCode, unitPrice, "ON_SALE");
  }

  /** 販売終了の商品を登録し、商品 ID を返す。 */
  public static UUID discontinued(
      final DSLContext dsl, final String productCode, final String unitPrice) {
    return insert(dsl, productCode, unitPrice, "DISCONTINUED");
  }

  private static UUID insert(
      final DSLContext dsl,
      final String productCode,
      final String unitPrice,
      final String salesStatus) {
    final UUID productId = UUID.randomUUID();
    TestCommonColumns.runAs(
        () ->
            insert(
                dsl,
                productId,
                productCode,
                "name of " + productCode,
                new BigDecimal(unitPrice),
                salesStatus,
                REGISTERED_AT));
    return productId;
  }

  /** 商品の行を 1 件登録する。{@code *_pgm_cd} は束縛しないので、呼ぶ側が {@link TestCommonColumns#runAs} の中で呼ぶ。 */
  public static void insert(
      final DSLContext dsl,
      final UUID publicId,
      final String productCode,
      final String productName,
      final BigDecimal unitPrice,
      final String salesStatus,
      final Instant registeredAt) {
    dsl.insertInto(M_PRODUCT)
        .set(M_PRODUCT.PUBLIC_ID, publicId)
        .set(M_PRODUCT.PRODUCT_CODE, productCode)
        .set(M_PRODUCT.PRODUCT_NAME, productName)
        .set(M_PRODUCT.UNIT_PRICE_JPY, unitPrice)
        .set(M_PRODUCT.SALES_STATUS_TYP, salesStatus)
        .set(TestCommonColumns.at(registeredAt).forInsert(M_PRODUCT))
        .execute();
  }
}
