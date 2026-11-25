package com.example.demo.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.demo.product.ProductQueries;
import com.example.demo.product.ProductSummary;
import com.example.demo.testkit.BindTestTenantExtension;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;

/** 商品の登録を、登録した商品を ProductQueries で読んで検証する。 */
@ApplicationModuleTest
@Import(SharedTestConfiguration.class)
@ExtendWith({CleanGeneratedTablesExtension.class, BindTestTenantExtension.class})
class RegisterProductCommandHandlerTest {

  /** テスト対象の CommandHandler。 */
  @Autowired private RegisterProductCommandHandler registerProduct;

  /** 登録した商品を読む参照のインタフェース。 */
  @Autowired private ProductQueries productQueries;

  @Test
  @DisplayName("登録した商品は、返した ID で、渡した値と販売の状態のまま読める")
  void registeredProductIsFoundById() {
    final String onSaleCode = UniqueCodes.next("P");
    final String discontinuedCode = UniqueCodes.next("P");

    final String onSale =
        registerProduct
            .handle(
                new RegisterProductCommand(
                    onSaleCode, "ボールペン", new BigDecimal("120.00"), "ON_SALE"))
            .productId();
    final String discontinued =
        registerProduct
            .handle(
                new RegisterProductCommand(
                    discontinuedCode, "消しゴム", new BigDecimal("80.00"), "DISCONTINUED"))
            .productId();

    assertThat(productQueries.findByIds(List.of(onSale, discontinued)))
        .extracting(
            ProductSummary::productId,
            ProductSummary::productCode,
            ProductSummary::productName,
            ProductSummary::salesStatus,
            ProductSummary::onSale)
        .containsExactlyInAnyOrder(
            tuple(onSale, onSaleCode, "ボールペン", "ON_SALE", true),
            tuple(discontinued, discontinuedCode, "消しゴム", "DISCONTINUED", false));
  }
}
