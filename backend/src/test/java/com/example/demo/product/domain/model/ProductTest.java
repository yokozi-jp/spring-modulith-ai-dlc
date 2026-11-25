package com.example.demo.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 商品の登録を検証する。 */
class ProductTest {

  @Test
  @DisplayName("登録した商品は UUID v4 の ID、ロック番号 1 と、渡した値を持ち、登録ごとに別の ID になる")
  void registerCreatesProduct() {
    final Product product =
        Product.register("P-0001", "ボールペン", new BigDecimal("120.00"), SalesStatus.DISCONTINUED);

    assertThat(product.id().value().version()).as("商品 ID の UUID の版").isEqualTo(4);
    assertThat(product.lockNo()).isEqualTo(1L);
    assertThat(product.productCode()).isEqualTo("P-0001");
    assertThat(product.productName()).isEqualTo("ボールペン");
    assertThat(product.unitPrice()).isEqualByComparingTo("120.00");
    assertThat(product.salesStatus()).isEqualTo(SalesStatus.DISCONTINUED);
    assertThat(Product.register("P-0001", "ボールペン", BigDecimal.ONE, SalesStatus.ON_SALE).id())
        .isNotEqualTo(product.id());
  }
}
