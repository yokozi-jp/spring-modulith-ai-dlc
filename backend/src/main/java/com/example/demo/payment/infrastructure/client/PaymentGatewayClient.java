package com.example.demo.payment.infrastructure.client;

import com.example.demo.payment.domain.model.ChargeOutcome;
import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 決済代行の HTTP API で、注文 ID を冪等性キーにして注文の代金を請求する。
 *
 * <p>タイムアウト、circuit breaker、retry は ADR-019 の既定値のままにする。決済代行の SLO がまだないため、値を変える根拠がない。retry は {@code
 * ChargeOrderCommandHandler} のトランザクションの中で呼ぶため default を継承して試行 1 回にし、失敗した請求はイベント出版の再投入でやり直す。
 * 再投入で同じ注文をもう一度請求しても、冪等性キーで二重の請求を防ぐ。
 *
 * <p>結果は ADR-072 の四つの分類に分ける。拒否（{@code DECLINED}）と契約の不備（401、403、429 以外の 4xx）は結果で返し、呼び出し元が業務の状態に記録する。
 * 一時障害（接続の失敗、タイムアウト、429、5xx）と資格情報の不備（401、403）は例外を投げ、イベント出版を FAILED に残して再投入を待つ。circuit breaker は 429
 * 以外の {@link HttpClientErrorException} を失敗に数えない（application.yaml の {@code
 * ignore-exception-predicate}）。
 */
@Slf4j
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

  /** 決済代行が請求を拒否したときの状態。 */
  private static final String DECLINED = "DECLINED";

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
   * 注文の代金を請求し、業務の状態に記録する結果を返す。
   *
   * <p>応答の本文がない、または状態が成功でも拒否でもないときは {@link IllegalStateException} を投げ、成功していない請求を支払い済みとして記録しない。
   */
  @CircuitBreaker(name = "payment-gateway")
  @Retry(name = "payment-gateway")
  @Override
  public ChargeOutcome charge(final OrderId orderId, final Money amount) {
    final String key = orderId.value().toString();
    final ChargeReply reply;
    try {
      reply = post(key, amount);
    } catch (HttpClientErrorException exception) {
      return contractError(key, exception);
    }
    return toOutcome(key, reply);
  }

  /** 冪等性キーを付けて請求の API を呼び、応答の本文を返す。 */
  private @Nullable ChargeReply post(final String key, final Money amount) {
    return restClient
        .post()
        .uri("/v1/charges")
        .header(IDEMPOTENCY_KEY, key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ChargeBody(key, amount.amount(), CURRENCY))
        .retrieve()
        .body(ChargeReply.class);
  }

  /**
   * 4xx を分類する。資格情報の不備（401、403）と流量の制限（429）は、直したあとや時間をおいた再投入で回復できるため投げ直す。
   * それ以外は同じ要求を再投入しても回復しない契約の不備なので、例外を投げずに決済の失敗を記録させる。
   */
  private static ChargeOutcome contractError(
      final String key, final HttpClientErrorException exception) {
    final int status = exception.getStatusCode().value();
    if (status == HttpStatus.UNAUTHORIZED.value()
        || status == HttpStatus.FORBIDDEN.value()
        || status == HttpStatus.TOO_MANY_REQUESTS.value()) {
      throw exception;
    }
    log.warn(
        "Payment gateway rejected the charge request as a contract error: orderId={}, status={}",
        key,
        status);
    return ChargeOutcome.failed();
  }

  /** 201 の応答の本文を、受付か拒否の結果に変える。 */
  private static ChargeOutcome toOutcome(final String key, final @Nullable ChargeReply reply) {
    if (reply == null) {
      throw new IllegalStateException("payment gateway returned no body: orderId=" + key);
    }
    final String chargeId = reply.chargeId();
    if (DECLINED.equals(reply.status())) {
      return ChargeOutcome.declined(chargeId == null ? null : new GatewayPaymentCode(chargeId));
    }
    if (!SUCCEEDED.equals(reply.status()) || chargeId == null) {
      throw new IllegalStateException(
          "payment gateway did not succeed: orderId=" + key + ", status=" + reply.status());
    }
    return ChargeOutcome.paid(new GatewayPaymentCode(chargeId));
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
