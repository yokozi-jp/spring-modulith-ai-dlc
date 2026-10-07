package com.example.demo.payment.infrastructure.client;

import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * 決済代行の偽物。通信せず、設定値で常に成功か常に失敗にする。
 *
 * <p>成功のときは、注文 ID から決めた識別子を返すため、同じ冪等キーには同じ識別子を返す。失敗のときは、通信の失敗と同じ {@link ResourceAccessException}
 * を投げる。通信しないため、接続と呼び出しのタイムアウトを持たない。circuit breaker と retry は ADR-019 の既定値をそのまま使う（retry は default
 * を継承して試行 1 回）。
 *
 * <p>retry は CommandHandler のトランザクションの中で呼ぶため試行 1 回にし、やり直しはイベント出版の再投入に任せる。circuit breaker は依存先の SLO
 * がない偽物なので既定値のままにする。直近 20 回の窓で 10 回以上呼ばれ、失敗率が 50% 以上のとき（FAIL では 10 回目の失敗で）open になり、{@code
 * CallNotPermittedException} を投げる。
 */
@Component
class PaymentGatewayClient implements PaymentGateway {

  /** 成功のときに返す識別子の接頭辞。 */
  private static final String CODE_PREFIX = "fake-";

  /** 常に成功か常に失敗か。 */
  private final Mode mode;

  /** 設定値 {@code payment-gateway.mode}（SUCCEED か FAIL）を受け取る。 */
  /* package */ PaymentGatewayClient(@Value("${payment-gateway.mode}") final String mode) {
    this.mode = Mode.parse(mode);
  }

  /** 失敗の設定なら通信の失敗を投げ、成功の設定なら注文 ID から決めた識別子を返す。 */
  @CircuitBreaker(name = "payment-gateway")
  @Retry(name = "payment-gateway")
  @Override
  public GatewayPaymentCode charge(final OrderId orderId, final Money amount) {
    if (mode == Mode.FAIL) {
      throw new ResourceAccessException(
          "payment gateway is configured to fail: orderId=" + orderId.value());
    }
    return new GatewayPaymentCode(CODE_PREFIX + orderId.value());
  }

  /** 偽物の振る舞い。 */
  private enum Mode {
    /** 常に成功する。 */
    SUCCEED,
    /** 常に失敗する。 */
    FAIL;

    /** 設定値を読む。値が許された値でなければ起動を失敗させる。 */
    /* package */ static Mode parse(final String value) {
      try {
        return valueOf(value);
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException(
            "payment-gateway.mode must be SUCCEED or FAIL: mode=" + value, exception);
      }
    }
  }
}
