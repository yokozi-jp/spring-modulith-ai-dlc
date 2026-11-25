package com.example.demo.product.presentation.web;

import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static com.example.demo.testkit.AppUsers.oidcLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.product.TestProducts;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.ContractCase;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;

/** 商品の登録と一覧の API のステータスコード、Location、Problem Details を HTTP の形で検証する。 */
// MockMvc の期待値は andExpect で書く。一つの API の契約を一つのクラスで確かめるため、同じパスと値と static import が多い。
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class ProductApiTest {

  /** 登録した商品の Location の形。 */
  private static final String LOCATION_PATTERN =
      "^http://localhost/api/products/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 商品の行を登録する jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("商品の一覧を商品コードの昇順で items に入れて返す")
  @ContractCase(method = RequestMethod.GET, path = "/api/products", status = 200)
  void listsProductsOrderedByCode() throws Exception {
    final String base = UniqueCodes.next("P");
    final UUID pen = TestProducts.onSale(dsl, base + "B", "120.00");
    final UUID eraser = TestProducts.discontinued(dsl, base + "A", "80.00");

    mockMvc
        .perform(get("/api/products").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].productId").value(eraser.toString()))
        .andExpect(jsonPath("$.items[0].productCode").value(base + "A"))
        .andExpect(jsonPath("$.items[0].productName").value("name of " + base + "A"))
        .andExpect(jsonPath("$.items[0].unitPrice").value(80.00))
        .andExpect(jsonPath("$.items[0].salesStatus").value("DISCONTINUED"))
        .andExpect(jsonPath("$.items[1].productId").value(pen.toString()))
        .andExpect(jsonPath("$.items[1].productCode").value(base + "B"))
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

  @Test
  @DisplayName("テナントの MEMBER が商品を登録すると 201 と Location を返し、選んだテナントに、利用者と登録のユースケースを記録して保存する")
  @ContractCase(method = RequestMethod.POST, path = "/api/products", status = 201)
  void registerReturnsCreatedAndStoresInSelectedTenant() throws Exception {
    final String productCode = UniqueCodes.next("P");

    final String location =
        register(productCode, "120.50", "DISCONTINUED")
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.LOCATION);

    assertThat(location).as("POST /api/products の Location").matches(LOCATION_PATTERN);
    final String productId =
        Objects.requireNonNull(location).substring(location.lastIndexOf('/') + 1);
    mockMvc
        .perform(get("/api/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].productId").value(productId))
        .andExpect(jsonPath("$.items[0].productCode").value(productCode))
        .andExpect(jsonPath("$.items[0].productName").value("name of " + productCode))
        .andExpect(jsonPath("$.items[0].unitPrice").value(120.50))
        .andExpect(jsonPath("$.items[0].salesStatus").value("DISCONTINUED"));
    assertThat(
            AsModule.<List<String>>call(
                dsl,
                "product",
                tx ->
                    tx.select(
                            M_PRODUCT.TENANT_PUBLIC_ID,
                            M_PRODUCT.CREATED_BY,
                            M_PRODUCT.CREATED_PGM_CD)
                        .from(M_PRODUCT)
                        .where(M_PRODUCT.PUBLIC_ID.eq(UUID.fromString(productId)))
                        .fetchSingle(
                            row -> List.of(row.value1().toString(), row.value2(), row.value3()))))
        .isEqualTo(List.of(AppUsers.TENANT_ID, AppUsers.USER_ID, "product.RegisterProduct"));
  }

  @Test
  @DisplayName("同じ商品コードの商品を登録すると 409 を返し、先の商品だけが残る")
  @ContractCase(method = RequestMethod.POST, path = "/api/products", status = 409)
  void duplicateProductCodeReturnsConflict() throws Exception {
    final String productCode = UniqueCodes.next("P");
    register(productCode, "120.00", "ON_SALE").andExpect(status().isCreated());

    register(productCode, "80.00", "ON_SALE")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/unique-violation"))
        .andExpect(jsonPath("$.detail").doesNotExist());
    mockMvc
        .perform(get("/api/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unitPrice").value(120.00));
  }

  @Test
  @DisplayName("21 文字の商品コード、負の単価、定義にない販売の状態は、検証の 400 を返し、商品を登録しない")
  @ContractCase(method = RequestMethod.POST, path = "/api/products", status = 400)
  void invalidProductReturnsValidationProblem() throws Exception {
    final String tooLong = "P".repeat(21);
    final String valid = UniqueCodes.next("P");
    for (final ResultActions rejected :
        List.of(
            register(tooLong, "120.00", "ON_SALE"),
            register(valid, "-1.00", "ON_SALE"),
            register(valid, "120.001", "ON_SALE"),
            register(valid, "120.00", "on_sale"))) {
      rejected
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    }
    mockMvc
        .perform(get("/api/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  private ResultActions register(
      final String productCode, final String unitPrice, final String salesStatus) throws Exception {
    return mockMvc.perform(
        post("/api/products")
            .with(oidcLogin())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                String.format(
                    Locale.ROOT,
                    "{\"productCode\":\"%s\",\"productName\":\"name of %s\",\"unitPrice\":%s,\"salesStatus\":\"%s\"}",
                    productCode,
                    productCode,
                    unitPrice,
                    salesStatus)));
  }
}
