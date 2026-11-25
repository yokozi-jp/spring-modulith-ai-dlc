package com.example.demo.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.failure.BusinessRuleViolationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 商品の登録を検証する。 */
class ProductTest {

  /** 商品コード。 */
  private static final String CODE = "P-0001";

  /** 商品名。 */
  private static final String NAME = "ボールペン";

  @Test
  @DisplayName("登録した商品は、渡した値とロック番号 1 を持つ")
  void registerKeepsValues() {
    final Product product =
        Product.register(CODE, NAME, new BigDecimal("0.01"), SalesStatus.DISCONTINUED);

    assertThat(product.lockNo()).isEqualTo(1L);
    assertThat(product.productCode()).isEqualTo(CODE);
    assertThat(product.productName()).isEqualTo(NAME);
    assertThat(product.unitPrice()).isEqualByComparingTo("0.01");
    assertThat(product.salesStatus()).isEqualTo(SalesStatus.DISCONTINUED);
  }

  @Test
  @DisplayName("商品 ID は UUID v4 で、登録ごとに別の値になる")
  void registerAssignsNewVersion4Id() {
    final Product product = Product.register(CODE, NAME, BigDecimal.ONE, SalesStatus.ON_SALE);

    assertThat(product.id().value().version()).as("商品 ID の UUID の版").isEqualTo(4);
    assertThat(Product.register(CODE, NAME, BigDecimal.ONE, SalesStatus.ON_SALE).id())
        .isNotEqualTo(product.id());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "空の商品コード, '', ボールペン, 120.00, BLANK_PRODUCT_CODE",
    "空白だけの商品名, P-0001, '  ', 120.00, BLANK_PRODUCT_NAME",
    "0 円の単価, P-0001, ボールペン, 0.00, UNIT_PRICE_BELOW_MINIMUM",
    "0.01 円より小さい単価, P-0001, ボールペン, 0.009, UNIT_PRICE_BELOW_MINIMUM"
  })
  @DisplayName("不変条件に反する商品は、理由を付けた業務規則の違反になる")
  void registerRejectsInvalidProduct(
      final String name,
      final String productCode,
      final String productName,
      final BigDecimal unitPrice,
      final ProductFailure reason) {
    assertThatThrownBy(
            () -> Product.register(productCode, productName, unitPrice, SalesStatus.ON_SALE))
        .isInstanceOfSatisfying(
            BusinessRuleViolationException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }
}
