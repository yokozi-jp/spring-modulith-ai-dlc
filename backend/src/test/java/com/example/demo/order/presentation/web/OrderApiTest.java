package com.example.demo.order.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.product.TestProducts;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** 注文の API のステータスコード、Location、Problem Details を HTTP の形で検証する。 */
// MockMvc の期待値は andExpect で書く。一つの API の契約を一つのクラスで確かめるため、メソッドと static import が多い。
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
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
class OrderApiTest {

  /** 作成した注文の Location の形。 */
  private static final String LOCATION_PATTERN =
      "^http://localhost/api/orders/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 商品の行を登録する jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 販売中で単価 120 円の商品。 */
  private UUID pen;

  /** 販売中で単価 80 円の商品。 */
  private UUID eraser;

  /** 販売終了の商品。 */
  private UUID discontinued;

  @BeforeEach
  void registerProducts() {
    pen = TestProducts.onSale(dsl, "P-0001", "120.00");
    eraser = TestProducts.onSale(dsl, "P-0002", "80.00");
    discontinued = TestProducts.discontinued(dsl, "P-0003", "300.00");
  }

  @Test
  @DisplayName("下書きの注文を作ると 201 と Location を返し、詳細は DRAFT とロック番号 1 と明細の金額を返す")
  void draftReturnsCreatedAndDetails() throws Exception {
    final String location =
        draft("C-0001", pen, 2)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.LOCATION);
    assertThat(location).as("POST /api/orders の Location").matches(LOCATION_PATTERN);

    mockMvc
        .perform(get(Objects.requireNonNull(location)).with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerOrderCode").value("C-0001"))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.lockNo").value(1))
        .andExpect(jsonPath("$.totalAmount").value(240.00))
        .andExpect(jsonPath("$.orderedAt").value(endsWith("Z")))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].lineNumber").value(1))
        .andExpect(jsonPath("$.lines[0].productId").value(pen.toString()))
        .andExpect(jsonPath("$.lines[0].quantity").value(2))
        .andExpect(jsonPath("$.lines[0].unitPrice").value(120.00))
        .andExpect(jsonPath("$.lines[0].amount").value(240.00));
  }

  @Test
  @DisplayName("同じ客先注文番号の注文を作ると 409 を返し、先の注文は残る")
  void duplicateCustomerOrderCodeReturnsConflict() throws Exception {
    final String orderId = draftAndGetId("C-0001", pen, 1);

    expectProblem(draft("C-0001", eraser, 1), 409);
    details(orderId).andExpect(jsonPath("$.lines[0].productId").value(pen.toString()));
    mockMvc
        .perform(get("/api/orders").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  @DisplayName("存在しない商品で注文を作ると 422 を返し、注文を作らない")
  void draftWithMissingProductReturnsUnprocessable() throws Exception {
    expectProblem(draft("C-0001", UUID.randomUUID(), 1), 422);
    mockMvc
        .perform(get("/api/orders").with(oidcLogin()))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  @DisplayName("販売終了の商品で注文を作ると 422 を返す")
  void draftWithDiscontinuedProductReturnsUnprocessable() throws Exception {
    expectProblem(draft("C-0001", discontinued, 1), 422);
  }

  @Test
  @DisplayName("存在しない注文の詳細は 404 を返す")
  void missingOrderDetailsReturnsNotFound() throws Exception {
    expectProblem(details(UUID.randomUUID().toString()), 404);
  }

  @Test
  @DisplayName("UUID でない注文 ID の詳細は about:blank の 400 を返す")
  void malformedOrderIdReturnsBadRequest() throws Exception {
    expectProblem(details("not-a-uuid"), 400);
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"PUT, /lines", "POST, /confirm", "POST, /cancel"})
  @DisplayName("存在しない注文の明細の変更、確定、取消は 404 を返す")
  void operationOnMissingOrderReturnsNotFound(final String method, final String suffix)
      throws Exception {
    expectProblem(operate(method, UUID.randomUUID().toString(), suffix, 1), 404);
  }

  @Test
  @DisplayName("正しいロック番号で明細を置き換えると 204 を返し、明細と合計を置き換えてロック番号を進める")
  void changeLinesReplacesLines() throws Exception {
    final String orderId = draftAndGetId("C-0001", pen, 1);

    mockMvc
        .perform(
            put("/api/orders/" + orderId + "/lines")
                .with(oidcLogin())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    String.format(
                        Locale.ROOT,
                        "{\"lines\":[{\"productId\":\"%s\",\"quantity\":3},"
                            + "{\"productId\":\"%s\",\"quantity\":1}],\"lockNo\":1}",
                        eraser,
                        pen)))
        .andExpect(status().isNoContent());

    details(orderId)
        .andExpect(jsonPath("$.lockNo").value(2))
        .andExpect(jsonPath("$.totalAmount").value(360.00))
        .andExpect(jsonPath("$.lines.length()").value(2))
        .andExpect(jsonPath("$.lines[0].lineNumber").value(1))
        .andExpect(jsonPath("$.lines[0].productId").value(eraser.toString()))
        .andExpect(jsonPath("$.lines[1].lineNumber").value(2))
        .andExpect(jsonPath("$.lines[1].productId").value(pen.toString()));
  }

  @ParameterizedTest(name = "商品が販売終了で存在する={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("明細の変更で存在しない商品か販売終了の商品を指定すると 422 を返し、注文を変えない")
  void changeLinesWithUnavailableProductReturnsUnprocessable(final boolean exists)
      throws Exception {
    final String orderId = draftAndGetId("C-0001", pen, 1);
    final String before = detailsBody(orderId);

    expectProblem(changeLines(orderId, exists ? discontinued : UUID.randomUUID(), 1), 422);

    assertThat(detailsBody(orderId)).as("orderId=%s の 422 の後の詳細", orderId).isEqualTo(before);
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"PUT, /lines", "POST, /confirm", "POST, /cancel"})
  @DisplayName("古いロック番号の明細の変更、確定、取消は 409 を返し、注文を変えない")
  void staleLockNoReturnsConflict(final String method, final String suffix) throws Exception {
    final String orderId = draftAndGetId("C-0001", pen, 1);
    final String before = detailsBody(orderId);

    expectProblem(operate(method, orderId, suffix, 2), 409);

    assertThat(detailsBody(orderId)).as("orderId=%s の 409 の後の詳細", orderId).isEqualTo(before);
  }

  @Test
  @DisplayName("下書きの注文を確定すると 204 を返し、状態を CONFIRMED にする")
  void confirmMakesOrderConfirmed() throws Exception {
    final String orderId = draftAndGetId("C-0001", pen, 1);

    operate("POST", orderId, "/confirm", 1).andExpect(status().isNoContent());

    details(orderId)
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.lockNo").value(2));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"PUT, /lines", "POST, /confirm", "POST, /cancel"})
  @DisplayName("確定済みの注文の明細の変更、確定、取消は最新のロック番号でも 422 を返し、注文を変えない")
  void operationOnConfirmedOrderReturnsUnprocessable(final String method, final String suffix)
      throws Exception {
    final String orderId = draftAndGetId("C-0001", pen, 1);
    operate("POST", orderId, "/confirm", 1).andExpect(status().isNoContent());
    final String before = detailsBody(orderId);

    expectProblem(operate(method, orderId, suffix, 2), 422);

    assertThat(detailsBody(orderId)).as("orderId=%s の 422 の後の詳細", orderId).isEqualTo(before);
  }

  @Test
  @DisplayName("取り消した注文を確定すると 422 を返す")
  void confirmCancelledOrderReturnsUnprocessable() throws Exception {
    final String orderId = draftAndGetId("C-0001", pen, 1);
    operate("POST", orderId, "/cancel", 1).andExpect(status().isNoContent());
    details(orderId).andExpect(jsonPath("$.status").value("CANCELLED"));

    expectProblem(operate("POST", orderId, "/confirm", 2), 422);
  }

  @Test
  @DisplayName("一覧は状態で絞り込め、該当がなければ空の items を返す")
  void listFiltersByStatus() throws Exception {
    final String draftId = draftAndGetId("C-0001", pen, 1);
    final String confirmedId = draftAndGetId("C-0002", eraser, 1);
    operate("POST", confirmedId, "/confirm", 1).andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/orders").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
    mockMvc
        .perform(get("/api/orders").param("status", "DRAFT").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].orderId").value(draftId))
        .andExpect(jsonPath("$.items[0].customerOrderCode").value("C-0001"))
        .andExpect(jsonPath("$.items[0].totalAmount").value(120.00))
        .andExpect(jsonPath("$.items[0].lockNo").value(1));
    mockMvc
        .perform(get("/api/orders").param("status", "CANCELLED").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  @DisplayName("一覧の状態に定義にない値を指定すると 400 を返す")
  void listWithUnknownStatusReturnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/orders").param("status", "draft").with(oidcLogin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  private ResultActions draft(
      final String customerOrderCode, final UUID productId, final int quantity) throws Exception {
    return mockMvc.perform(
        post("/api/orders")
            .with(oidcLogin())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                String.format(
                    Locale.ROOT,
                    "{\"customerOrderCode\":\"%s\",\"lines\":[{\"productId\":\"%s\",\"quantity\":%d}]}",
                    customerOrderCode,
                    productId,
                    quantity)));
  }

  private String draftAndGetId(
      final String customerOrderCode, final UUID productId, final int quantity) throws Exception {
    final String location =
        draft(customerOrderCode, productId, quantity)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.LOCATION);
    final String path = Objects.requireNonNull(location, "POST /api/orders の Location");
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private ResultActions details(final String orderId) throws Exception {
    return mockMvc.perform(get("/api/orders/" + orderId).with(oidcLogin()));
  }

  private String detailsBody(final String orderId) throws Exception {
    return details(orderId)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** 明細の変更（PUT /lines）、確定（POST /confirm）、取消（POST /cancel）を送る。 */
  private ResultActions operate(
      final String method, final String orderId, final String suffix, final long lockNo)
      throws Exception {
    final MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(
                HttpMethod.valueOf(method), "/api/orders/" + orderId + suffix)
            .with(oidcLogin())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(String.format(Locale.ROOT, "{\"lockNo\":%d}", lockNo));
    return "/lines".equals(suffix)
        ? changeLines(orderId, eraser, lockNo)
        : mockMvc.perform(request);
  }

  /** 明細を商品 1 つ、数量 5 の 1 行に置き換える PUT /lines を送る。 */
  private ResultActions changeLines(final String orderId, final UUID productId, final long lockNo)
      throws Exception {
    return mockMvc.perform(
        put("/api/orders/" + orderId + "/lines")
            .with(oidcLogin())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                String.format(
                    Locale.ROOT,
                    "{\"lines\":[{\"productId\":\"%s\",\"quantity\":5}],\"lockNo\":%d}",
                    productId,
                    lockNo)));
  }

  /** about:blank の Problem Details で、detail を持たないことを確かめる。 */
  private static void expectProblem(final ResultActions actions, final int status)
      throws Exception {
    actions
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.title").exists())
        .andExpect(jsonPath("$.detail").doesNotExist());
  }
}
