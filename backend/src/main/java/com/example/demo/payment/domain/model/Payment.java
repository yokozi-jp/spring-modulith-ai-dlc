package com.example.demo.payment.domain.model;

import java.time.Instant;
import java.util.UUID;

/** 決済記録の集約ルート。決済代行の請求が成功したときだけ作り、変更しない。 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
// 決済記録は請求の成功を記録するだけで変更しないため、振る舞いのないアクセサだけの集約になる。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName", "PMD.DataClass"})
public final class Payment {

  /** 決済記録の ID。 */
  private final PaymentId id;

  /** 決済した注文の ID。 */
  private final OrderId orderId;

  /** 請求した金額。 */
  private final Money amount;

  /** 決済代行が採番した決済の識別子。 */
  private final GatewayPaymentCode gatewayPaymentCode;

  /** 決済した時刻。 */
  private final Instant paidAt;

  /** 楽観的ロックのロック番号。 */
  private final long lockNo;

  private Payment(
      final PaymentId id,
      final OrderId orderId,
      final Money amount,
      final GatewayPaymentCode gatewayPaymentCode,
      final Instant paidAt,
      final long lockNo) {
    this.id = id;
    this.orderId = orderId;
    this.amount = amount;
    this.gatewayPaymentCode = gatewayPaymentCode;
    this.paidAt = paidAt;
    this.lockNo = lockNo;
  }

  /**
   * 請求に成功した決済を記録する。決済記録の ID は UUID v4 で採番する（ADR-060）。
   *
   * @param orderId 決済した注文の ID
   * @param amount 請求した金額
   * @param gatewayPaymentCode 決済代行が採番した決済の識別子
   * @param paidAt 決済した時刻
   */
  public static Payment record(
      final OrderId orderId,
      final Money amount,
      final GatewayPaymentCode gatewayPaymentCode,
      final Instant paidAt) {
    return new Payment(
        new PaymentId(UUID.randomUUID()), orderId, amount, gatewayPaymentCode, paidAt, 1L);
  }

  /** 保存済みの決済記録を復元する。Repository の実装が使う。 */
  public static Payment restore(
      final PaymentId id,
      final OrderId orderId,
      final Money amount,
      final GatewayPaymentCode gatewayPaymentCode,
      final Instant paidAt,
      final long lockNo) {
    return new Payment(id, orderId, amount, gatewayPaymentCode, paidAt, lockNo);
  }

  /** 決済記録の ID を返す。 */
  public PaymentId id() {
    return id;
  }

  /** 決済した注文の ID を返す。 */
  public OrderId orderId() {
    return orderId;
  }

  /** 請求した金額を返す。 */
  public Money amount() {
    return amount;
  }

  /** 決済代行が採番した決済の識別子を返す。 */
  public GatewayPaymentCode gatewayPaymentCode() {
    return gatewayPaymentCode;
  }

  /** 決済した時刻を返す。 */
  public Instant paidAt() {
    return paidAt;
  }

  /** ロック番号を返す。 */
  public long lockNo() {
    return lockNo;
  }
}
