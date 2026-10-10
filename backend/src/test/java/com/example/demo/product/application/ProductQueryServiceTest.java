package com.example.demo.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.demo.product.ProductQueries;
import com.example.demo.product.ProductSummary;
import com.example.demo.product.TestProducts;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;

/** 商品の参照を、ProductQueries を通して検証する。 */
@ApplicationModuleTest
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class ProductQueryServiceTest {

  /** テスト対象の参照のインタフェース。 */
  @Autowired private ProductQueries productQueries;

  /** 商品の行を登録する jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("onSale は、販売中の商品だけ true を返す")
  void onSaleIsTrueOnlyForProductOnSale() {
    final UUID pen = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    final UUID eraser = TestProducts.discontinued(dsl, UniqueCodes.next("P"), "80.00");

    assertThat(productQueries.findByIds(List.of(pen.toString(), eraser.toString())))
        .extracting(ProductSummary::productId, ProductSummary::salesStatus, ProductSummary::onSale)
        .containsExactlyInAnyOrder(
            tuple(pen.toString(), "ON_SALE", true),
            tuple(eraser.toString(), "DISCONTINUED", false));
  }
}
