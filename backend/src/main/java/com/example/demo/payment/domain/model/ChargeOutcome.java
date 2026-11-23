package com.example.demo.payment.domain.model;

/**
 * 決済代行への請求の、業務の状態に記録する結果。
 *
 * <p>一時障害と資格情報の不備は結果にせず、外部システムの実装が例外を投げる（ADR-072）。
 */
public sealed interface ChargeOutcome {

  /** 決済代行が受け付けた請求。 */
  record Paid(GatewayPaymentCode gatewayPaymentCode) implements ChargeOutcome {}

  /** 決済代行が拒否した請求。 */
  record Declined(GatewayPaymentCode gatewayPaymentCode) implements ChargeOutcome {}

  /** 契約の不備（契約に合わない応答を含む）で失敗した請求。決済代行の識別子を持たない。 */
  record Failed() implements ChargeOutcome {}

  /** 決済代行が受け付けた請求。 */
  static ChargeOutcome paid(final GatewayPaymentCode gatewayPaymentCode) {
    return new Paid(gatewayPaymentCode);
  }

  /** 決済代行が拒否した請求。 */
  static ChargeOutcome declined(final GatewayPaymentCode gatewayPaymentCode) {
    return new Declined(gatewayPaymentCode);
  }

  /** 契約の不備で失敗した請求。 */
  static ChargeOutcome failed() {
    return new Failed();
  }

  /** 決済記録に書く状態を返す。 */
  default PaymentStatus status() {
    return switch (this) {
      case Paid _ -> PaymentStatus.PAID;
      case Declined _ -> PaymentStatus.DECLINED;
      case Failed _ -> PaymentStatus.FAILED;
    };
  }
}
