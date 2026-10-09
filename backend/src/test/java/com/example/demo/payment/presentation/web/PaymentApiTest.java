package com.example.demo.payment.presentation.web;

import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 注文の決済記録の API を HTTP の形で検証する。 */
// MockMvc の期待値は andExpect で書き、AssertJ の assert を使わないため、static import が多い。
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
@SuppressWarnings({
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class PaymentApiTest {

  /** 請求の結果を記録した時刻の、API が返す形。 */
  private static final String RECORDED_AT_TEXT = "2026-10-06T01:02:03.123456Z";

  /** 請求の結果を記録した時刻。 */
  private static final Instant RECORDED_AT = Instant.parse(RECORDED_AT_TEXT);

  /** 決済代行の識別子の接頭辞。 */
  private static final String CHARGE_ID_PREFIX = "ch_";

  /** 1 件目の決済記録の、記録した時刻の JSON path。 */
  private static final String FIRST_RECORDED_AT = "$.items[0].recordedAt";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 決済記録の行を登録する jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("決済記録がある注文では、items に 1 件を入れ、recordedAt を Z 付きで返す")
  void listsPaymentOfOrder() throws Exception {
    final UUID orderId = UUID.randomUUID();
    final UUID paymentId = insertPayment(orderId);

    mockMvc
        .perform(payments(orderId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.items[0].orderId").value(orderId.toString()))
        .andExpect(jsonPath("$.items[0].amount").value(240.00))
        .andExpect(jsonPath("$.items[0].status").value("PAID"))
        .andExpect(jsonPath("$.items[0].gatewayPaymentCode").value(CHARGE_ID_PREFIX + orderId))
        .andExpect(jsonPath(FIRST_RECORDED_AT).value(RECORDED_AT_TEXT))
        .andExpect(jsonPath(FIRST_RECORDED_AT).value(endsWith("Z")));
  }

  @Test
  @DisplayName("契約の不備で失敗した注文では、status を FAILED にし、識別子を省き、記録した時刻を返す")
  void listsFailedPaymentWithRecordedAt() throws Exception {
    final UUID orderId = UUID.randomUUID();
    insertPayment(orderId, "FAILED", "");

    mockMvc
        .perform(payments(orderId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].status").value("FAILED"))
        .andExpect(jsonPath("$.items[0].gatewayPaymentCode").doesNotExist())
        .andExpect(jsonPath(FIRST_RECORDED_AT).value(RECORDED_AT_TEXT));
  }

  @Test
  @DisplayName("拒否された注文では、status を DECLINED にし、識別子と記録した時刻を返す")
  void listsDeclinedPaymentWithRecordedAt() throws Exception {
    final UUID orderId = UUID.randomUUID();
    insertPayment(orderId, "DECLINED", CHARGE_ID_PREFIX + orderId);

    mockMvc
        .perform(payments(orderId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].status").value("DECLINED"))
        .andExpect(jsonPath("$.items[0].gatewayPaymentCode").value(CHARGE_ID_PREFIX + orderId))
        .andExpect(jsonPath(FIRST_RECORDED_AT).value(RECORDED_AT_TEXT));
  }

  @Test
  @DisplayName("決済記録がない注文では、空の items を 200 で返す")
  void listsNoPayment() throws Exception {
    insertPayment(UUID.randomUUID());

    mockMvc
        .perform(payments(UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  @DisplayName("orderId がなければ 400 の Problem Details を返す")
  void missingOrderIdReturnsBadRequest() throws Exception {
    expectBadRequest(get("/api/payments").with(oidcLogin()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-uuid", ""})
  @DisplayName("orderId が UUID でなければ 400 の Problem Details を返す")
  void invalidOrderIdReturnsBadRequest(final String orderId) throws Exception {
    expectBadRequest(payments(orderId));
  }

  private static MockHttpServletRequestBuilder payments(final String orderId) {
    return get("/api/payments").param("orderId", orderId).with(oidcLogin());
  }

  private void expectBadRequest(final MockHttpServletRequestBuilder request) throws Exception {
    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").exists());
  }

  private UUID insertPayment(final UUID orderId) {
    return insertPayment(orderId, "PAID", CHARGE_ID_PREFIX + orderId);
  }

  private UUID insertPayment(final UUID orderId, final String status, final String code) {
    final UUID paymentId = UUID.randomUUID();
    TestCommonColumns.runAs(
        () ->
            dsl.insertInto(T_PAYMENT)
                .set(T_PAYMENT.PUBLIC_ID, paymentId)
                .set(T_PAYMENT.ORDER_PUBLIC_ID, orderId)
                .set(T_PAYMENT.CHARGED_AMOUNT_JPY, new BigDecimal("240.00"))
                .set(T_PAYMENT.PAYMENT_STATUS_TYP, status)
                .set(T_PAYMENT.GATEWAY_PAYMENT_CODE, code)
                .set(T_PAYMENT.RECORDED_AT, RECORDED_AT)
                .set(TestCommonColumns.at(RECORDED_AT).forInsert(T_PAYMENT))
                .execute());
    return paymentId;
  }
}
