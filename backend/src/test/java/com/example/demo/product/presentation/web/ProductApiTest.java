package com.example.demo.product.presentation.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.product.TestProducts;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** 商品の一覧の API を HTTP の形で検証する。 */
// MockMvc の期待値は andExpect で書き、AssertJ の assert を使わない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class ProductApiTest {

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 商品の行を登録する jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("商品の一覧を商品コードの昇順で items に入れて返す")
  void listsProductsOrderedByCode() throws Exception {
    final UUID pen = TestProducts.onSale(dsl, "P-0002", "120.00");
    final UUID eraser = TestProducts.discontinued(dsl, "P-0001", "80.00");

    mockMvc
        .perform(get("/api/products").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].productId").value(eraser.toString()))
        .andExpect(jsonPath("$.items[0].productCode").value("P-0001"))
        .andExpect(jsonPath("$.items[0].productName").value("name of P-0001"))
        .andExpect(jsonPath("$.items[0].unitPrice").value(80.00))
        .andExpect(jsonPath("$.items[0].salesStatus").value("DISCONTINUED"))
        .andExpect(jsonPath("$.items[1].productId").value(pen.toString()))
        .andExpect(jsonPath("$.items[1].salesStatus").value("ON_SALE"));
  }

  @Test
  @DisplayName("商品がなければ空の items を 200 で返す")
  void listsNoProducts() throws Exception {
    mockMvc
        .perform(get("/api/products").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }
}
