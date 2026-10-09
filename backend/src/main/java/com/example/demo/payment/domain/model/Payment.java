package com.example.demo.payment.domain.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 決済記録の集約ルート。決済代行への請求の結果（受付、拒否、契約の不備による失敗）が決まったときに作り、変更しない。
 *
 * <p>一時障害と資格情報の不備では作らず、イベント出版を FAILED に残して再投入を待つ（ADR-072）。
 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
// 決済記録は請求の結果を記録するだけで変更しないため、振る舞いのないアクセサだけの集約になる。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName", "PMD.DataClass"})
public final class Payment {

  /** 決済記録の ID。 */
  private final PaymentId id;

  /** 請求した注文の ID。 */
  private final OrderId orderId;

  /** 請求した金額。 */
  private final Money amount;

  /** 請求の結果。 */
  private final PaymentStatus status;

  /** 決済代行が採番した決済の識別子。採番されなかったときは null。 */
  private final @Nullable GatewayPaymentCode gatewayPaymentCode;

  /** 請求の結果を記録した時刻。 */
  private final Instant recordedAt;

  /** 楽観的ロックのロック番号。 */
  private final long lockNo;

  private Payment(
      final PaymentId id,
      final OrderId orderId,
      final Money amount,
      final ChargeOutcome outcome,
      final Instant recordedAt,
      final long lockNo) {
    this.id = id;
    this.orderId = orderId;
    this.amount = amount;
    this.status = outcome.status();
    this.gatewayPaymentCode = outcome.gatewayPaymentCode();
    this.recordedAt = recordedAt;
    this.lockNo = lockNo;
  }

  /**
   * 請求の結果を記録する。決済記録の ID は UUID v4 で採番する（ADR-060）。
   *
   * @param orderId 請求した注文の ID
   * @param amount 請求した金額
   * @param outcome 請求の結果
   * @param recordedAt 請求の結果を記録した時刻
   */
  public static Payment record(
      final OrderId orderId,
      final Money amount,
      final ChargeOutcome outcome,
      final Instant recordedAt) {
    return new Payment(new PaymentId(UUID.randomUUID()), orderId, amount, outcome, recordedAt, 1L);
  }

  /** 保存済みの決済記録を復元する。Repository の実装が使う。 */
  public static Payment restore(
      final PaymentId id,
      final OrderId orderId,
      final Money amount,
      final ChargeOutcome outcome,
      final Instant recordedAt,
      final long lockNo) {
    return new Payment(id, orderId, amount, outcome, recordedAt, lockNo);
  }

  /** 決済記録の ID を返す。 */
  public PaymentId id() {
    return id;
  }

  /** 請求した注文の ID を返す。 */
  public OrderId orderId() {
    return orderId;
  }

  /** 請求した金額を返す。 */
  public Money amount() {
    return amount;
  }

  /** 請求の結果を返す。 */
  public PaymentStatus status() {
    return status;
  }

  /** 決済代行が採番した決済の識別子を返す。採番されなかったときは null。 */
  public @Nullable GatewayPaymentCode gatewayPaymentCode() {
    return gatewayPaymentCode;
  }

  /** 請求の結果を記録した時刻を返す。 */
  public Instant recordedAt() {
    return recordedAt;
  }

  /** ロック番号を返す。 */
  public long lockNo() {
    return lockNo;
  }
}
