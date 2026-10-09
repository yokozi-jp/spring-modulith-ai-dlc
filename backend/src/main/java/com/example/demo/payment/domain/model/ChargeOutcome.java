package com.example.demo.payment.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * 決済代行への請求の、業務の状態に記録する結果。
 *
 * <p>一時障害と資格情報の不備は結果にせず、外部システムの実装が例外を投げる（ADR-072）。
 *
 * @param status 請求の結果
 * @param gatewayPaymentCode 決済代行が採番した決済の識別子。受付と拒否では必ずあり、契約の不備のときは null
 */
public record ChargeOutcome(PaymentStatus status, @Nullable GatewayPaymentCode gatewayPaymentCode) {

  /** 受け付けた請求と拒否した請求には、決済代行の識別子があることを確かめる（ADR-072 の契約）。 */
  public ChargeOutcome {
    if (status != PaymentStatus.FAILED && gatewayPaymentCode == null) {
      throw new IllegalArgumentException(status + " charge must have gatewayPaymentCode");
    }
  }

  /** 決済代行が受け付けた請求。 */
  public static ChargeOutcome paid(final GatewayPaymentCode gatewayPaymentCode) {
    return new ChargeOutcome(PaymentStatus.PAID, gatewayPaymentCode);
  }

  /** 決済代行が拒否した請求。 */
  public static ChargeOutcome declined(final GatewayPaymentCode gatewayPaymentCode) {
    return new ChargeOutcome(PaymentStatus.DECLINED, gatewayPaymentCode);
  }

  /** 契約の不備（契約に合わない応答を含む）で失敗した請求。 */
  public static ChargeOutcome failed() {
    return new ChargeOutcome(PaymentStatus.FAILED, null);
  }
}
