package com.example.demo.product.presentation.web;

import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static com.example.demo.testkit.AppUsers.inTenant;
import static com.example.demo.testkit.AppUsers.oidcLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.product.TestProducts;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.ContractCase;
import com.example.demo.testkit.IdempotencyKeys;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;

/** 商品の登録、一覧、1 件の API のステータスコード、Location、Problem Details を HTTP の形で検証する。 */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyMethods",
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
      "^http://localhost/api/v1/products/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 商品の行を登録する jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("商品の一覧を商品コードの昇順で items に入れて返す")
  @ContractCase(method = RequestMethod.GET, path = "/api/v1/products", status = 200)
  void listsProductsOrderedByCode() throws Exception {
    final String base = UniqueCodes.next("P");
    final UUID pen = TestProducts.onSale(dsl, base + "B", "120.00");
    final UUID eraser = TestProducts.discontinued(dsl, base + "A", "80.00");

    mockMvc
        .perform(get("/api/v1/products").with(oidcLogin()))
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
        .perform(get("/api/v1/products").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  @DisplayName("商品を登録すると 201 を返し、Location で登録した値の商品を読める")
  @ContractCase(method = RequestMethod.POST, path = "/api/v1/products", status = 201)
  @ContractCase(method = RequestMethod.GET, path = "/api/v1/products/{productId}", status = 200)
  void registerReturnsLocationOfRegisteredProduct() throws Exception {
    final String productCode = UniqueCodes.next("P");

    final String location = location(register(productCode, "120.50", "DISCONTINUED"));

    assertThat(location).as("POST /api/v1/products の Location").matches(LOCATION_PATTERN);
    mockMvc
        .perform(get(location).with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productId").value(productId(location)))
        .andExpect(jsonPath("$.productCode").value(productCode))
        .andExpect(jsonPath("$.productName").value("name of " + productCode))
        .andExpect(jsonPath("$.unitPrice").value(120.50))
        .andExpect(jsonPath("$.salesStatus").value("DISCONTINUED"));
  }

  @Test
  @DisplayName("登録した商品は、選んだテナントに、利用者と登録の操作の名前を記録して保存する")
  void registerStoresInSelectedTenantWithAuditColumns() throws Exception {
    final String productId =
        productId(location(register(UniqueCodes.next("P"), "120.00", "ON_SALE")));

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
  @DisplayName("同じキーと同じ本文の再送は、1 回目と同じ 201 と Location を返し、商品を 1 件だけ登録する")
  void replayReturnsSameLocation() throws Exception {
    final String productCode = UniqueCodes.next("P");
    final IdempotencyKey key = IdempotencyKeys.next();

    final String first = location(register(oidcLogin(), key, productCode, "120.00", "ON_SALE"));
    final String replayed = location(register(oidcLogin(), key, productCode, "120.00", "ON_SALE"));

    assertThat(replayed).as("key=%s の再送の Location", key.value()).isEqualTo(first);
    mockMvc
        .perform(get("/api/v1/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  @DisplayName("同じキーで本文の違う要求は、ペイロードの不一致の 400 を返し、商品を登録しない")
  void differentPayloadReturnsBadRequest() throws Exception {
    final IdempotencyKey key = IdempotencyKeys.next();
    location(register(oidcLogin(), key, UniqueCodes.next("P"), "120.00", "ON_SALE"));

    register(oidcLogin(), key, UniqueCodes.next("P"), "120.00", "ON_SALE")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/idempotency-key-payload-mismatch"))
        .andExpect(jsonPath("$.detail").doesNotExist());
    mockMvc
        .perform(get("/api/v1/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  @DisplayName("Idempotency-Key のない登録は、本文の検証より先にキーなしの 400 を返す")
  void missingKeyReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/products")
                .with(oidcLogin())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/idempotency-key-missing"));
  }

  @Test
  @DisplayName("2 つのテナントは同じ商品コードの商品を登録でき、どちらのテナントからも相手の商品は見えない")
  void sameProductCodeIsRegisteredPerTenant() throws Exception {
    final String productCode = UniqueCodes.next("P");
    final RequestPostProcessor otherTenant = inTenant(UUID.randomUUID().toString(), "MEMBER");
    final String location = location(register(oidcLogin(), productCode, "120.00", "ON_SALE"));

    final String otherLocation = location(register(otherTenant, productCode, "80.00", "ON_SALE"));

    mockMvc
        .perform(get("/api/v1/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unitPrice").value(120.00));
    mockMvc
        .perform(get("/api/v1/products").with(otherTenant))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unitPrice").value(80.00));
    mockMvc.perform(get(location).with(otherTenant)).andExpect(status().isNotFound());
    mockMvc
        .perform(get(otherLocation).with(otherTenant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unitPrice").value(80.00));
  }

  @Test
  @DisplayName("存在しない商品は 404 を返す")
  @ContractCase(method = RequestMethod.GET, path = "/api/v1/products/{productId}", status = 404)
  void missingProductReturnsNotFound() throws Exception {
    mockMvc
        .perform(get("/api/v1/products/" + UUID.randomUUID()).with(oidcLogin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("about:blank"));
  }

  @Test
  @DisplayName("UUID でない商品 ID は about:blank の 400 を返す")
  @ContractCase(method = RequestMethod.GET, path = "/api/v1/products/{productId}", status = 400)
  void malformedProductIdReturnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/v1/products/not-a-uuid").with(oidcLogin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("about:blank"));
  }

  @Test
  @DisplayName("同じ商品コードの商品を登録すると 409 を返し、先の商品だけが残る")
  @ContractCase(method = RequestMethod.POST, path = "/api/v1/products", status = 409)
  void duplicateProductCodeReturnsConflict() throws Exception {
    final String productCode = UniqueCodes.next("P");
    location(register(productCode, "120.00", "ON_SALE"));

    register(productCode, "80.00", "ON_SALE")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("/problems/unique-violation"))
        .andExpect(jsonPath("$.detail").doesNotExist());
    mockMvc
        .perform(get("/api/v1/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].unitPrice").value(120.00));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "21 文字の商品コード, PPPPPPPPPPPPPPPPPPPPP, 120.00, ON_SALE",
    "負の単価, P-0001, -1.00, ON_SALE",
    "0 円の単価, P-0001, 0.00, ON_SALE",
    "小数 3 桁の単価, P-0001, 120.001, ON_SALE",
    "定義にない販売の状態, P-0001, 120.00, on_sale"
  })
  @DisplayName("本文の形式の誤りは、検証の 400 を返し、商品を登録しない")
  @ContractCase(method = RequestMethod.POST, path = "/api/v1/products", status = 400)
  void invalidProductReturnsValidationProblem(
      final String name, final String productCode, final String unitPrice, final String salesStatus)
      throws Exception {
    register(productCode, unitPrice, salesStatus)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/validation-error"));
    mockMvc
        .perform(get("/api/v1/products").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  private ResultActions register(
      final String productCode, final String unitPrice, final String salesStatus) throws Exception {
    return register(oidcLogin(), productCode, unitPrice, salesStatus);
  }

  private ResultActions register(
      final RequestPostProcessor user,
      final String productCode,
      final String unitPrice,
      final String salesStatus)
      throws Exception {
    return register(user, IdempotencyKeys.next(), productCode, unitPrice, salesStatus);
  }

  private ResultActions register(
      final RequestPostProcessor user,
      final IdempotencyKey key,
      final String productCode,
      final String unitPrice,
      final String salesStatus)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/products")
            .with(user)
            .with(csrf())
            .with(IdempotencyKeys.header(key))
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

  /** 201 を確かめて、Location を返す。 */
  private static String location(final ResultActions registered) throws Exception {
    return Objects.requireNonNull(
        registered
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.LOCATION));
  }

  private static String productId(final String location) {
    return location.substring(location.lastIndexOf('/') + 1);
  }
}
