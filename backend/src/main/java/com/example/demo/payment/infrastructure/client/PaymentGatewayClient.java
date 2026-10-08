package com.example.demo.payment.infrastructure.client;

import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 決済代行の HTTP API で、注文 ID を冪等性キーにして注文の代金を請求する。
 *
 * <p>タイムアウト、circuit breaker、retry は ADR-019 の既定値のままにする。決済代行の SLO がまだないため、値を変える根拠がない。retry は {@code
 * ChargeOrderCommandHandler} のトランザクションの中で呼ぶため default を継承して試行 1 回にし、失敗した請求はイベント出版の再投入でやり直す。
 * 再投入で同じ注文をもう一度請求しても、冪等性キーで二重の請求を防ぐ。
 *
 * <p>決済代行の 4xx（{@link
 * org.springframework.web.client.HttpClientErrorException}）はこちらの要求や契約の誤りなので、circuit breaker
 * は失敗として数えない。 retry は試行 1 回なので、4xx も再試行しない。
 */
@Component
class PaymentGatewayClient implements PaymentGateway {

  /** 接続の確立を待つ上限。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

  /** 一回の呼び出しの応答を待つ上限。 */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

  /** 決済代行が冪等性キーを受け取るヘッダー。 */
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** 請求の通貨。{@link Money} は円の金額である。 */
  private static final String CURRENCY = "JPY";

  /** 請求が成功したときの状態。 */
  private static final String SUCCEEDED = "SUCCEEDED";

  /** 決済代行を呼ぶ HTTP クライアント。 */
  private final RestClient restClient;

  /** 決済代行の URL を受け取り、タイムアウトを設定した HTTP クライアントを作る。 */
  /* package */ PaymentGatewayClient(@Value("${payment-gateway.base-url}") final String baseUrl) {
    final JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    requestFactory.setReadTimeout(READ_TIMEOUT);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  /**
   * 注文の代金を請求し、決済代行が採番した識別子を返す。
   *
   * <p>応答の本文がない、または状態が成功でないときは {@link IllegalStateException} を投げ、成功していない請求を支払い済みとして記録しない。
   */
  @CircuitBreaker(name = "payment-gateway")
  @Retry(name = "payment-gateway")
  @Override
  public GatewayPaymentCode charge(final OrderId orderId, final Money amount) {
    final String key = orderId.value().toString();
    final ChargeReply reply =
        restClient
            .post()
            .uri("/v1/charges")
            .header(IDEMPOTENCY_KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ChargeBody(key, amount.amount(), CURRENCY))
            .retrieve()
            .body(ChargeReply.class);
    if (reply == null) {
      throw new IllegalStateException("payment gateway returned no body: orderId=" + key);
    }
    final String chargeId = reply.chargeId();
    if (!SUCCEEDED.equals(reply.status()) || chargeId == null) {
      throw new IllegalStateException(
          "payment gateway did not succeed: orderId=" + key + ", status=" + reply.status());
    }
    return new GatewayPaymentCode(chargeId);
  }

  /**
   * 請求の API に送る本文。
   *
   * @param orderId 請求する注文の ID
   * @param amount 請求する金額
   * @param currency 通貨
   */
  private record ChargeBody(String orderId, BigDecimal amount, String currency) {}

  /**
   * 請求の API が返す本文。
   *
   * @param chargeId 決済代行が採番した請求の識別子
   * @param status 請求の状態
   */
  private record ChargeReply(@Nullable String chargeId, @Nullable String status) {}
}
