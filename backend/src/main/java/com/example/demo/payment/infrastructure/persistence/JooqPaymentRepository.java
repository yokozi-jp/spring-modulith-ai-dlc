package com.example.demo.payment.infrastructure.persistence;

import static com.example.demo.jooq.payment.Tables.T_PAYMENT;

import com.example.demo.payment.domain.model.ChargeOutcome;
import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentId;
import com.example.demo.payment.domain.model.PaymentRepository;
import com.example.demo.payment.domain.model.PaymentStatus;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import java.time.Instant;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record7;
import org.jooq.Records;
import org.jooq.SelectJoinStep;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** 決済記録を jOOQ で保存し、取り出す。 */
@Repository
class JooqPaymentRepository implements PaymentRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る shared の共通処理。 */
  private final CommonColumns commonColumns;

  /** 集約ルートの INSERT を実行し、一意制約の違反を ConflictException に変える shared の入口。 */
  private final TableWriter tableWriter;

  /** jOOQ のコンテキストと shared の共通処理を受け取る。 */
  /* package */ JooqPaymentRepository(
      final DSLContext dsl, final CommonColumns commonColumns, final TableWriter tableWriter) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
    this.tableWriter = tableWriter;
  }

  @Override
  public Optional<Payment> findByOrderId(final OrderId orderId) {
    return selectPayments()
        .where(T_PAYMENT.ORDER_PUBLIC_ID.eq(orderId.value()))
        .fetchOptional(Records.mapping(JooqPaymentRepository::restore));
  }

  @Override
  public void add(final Payment payment) {
    // ユニークインデックスは public_id（UUID v4 で衝突しない）と order_public_id だけなので、409 は注文の決済記録の重複である。
    tableWriter.insert(
        dsl.insertInto(T_PAYMENT)
            .set(T_PAYMENT.PUBLIC_ID, payment.id().value())
            .set(T_PAYMENT.ORDER_PUBLIC_ID, payment.orderId().value())
            .set(T_PAYMENT.CHARGED_AMOUNT_JPY, payment.amount().amount())
            .set(T_PAYMENT.PAYMENT_STATUS_TYP, payment.status().name())
            .set(T_PAYMENT.GATEWAY_PAYMENT_CODE, codeValue(payment.gatewayPaymentCode()))
            .set(T_PAYMENT.RECORDED_AT, payment.recordedAt())
            .set(commonColumns.forInsert(T_PAYMENT)));
  }

  /** 決済記録の列を、restore の引数の型と順に選ぶ。 */
  private SelectJoinStep<Record7<PaymentId, OrderId, Money, PaymentStatus, String, Instant, Long>>
      selectPayments() {
    return dsl.select(
            T_PAYMENT.PUBLIC_ID.convertFrom(PaymentId::new),
            T_PAYMENT.ORDER_PUBLIC_ID.convertFrom(OrderId::new),
            T_PAYMENT.CHARGED_AMOUNT_JPY.convertFrom(Money::new),
            T_PAYMENT.PAYMENT_STATUS_TYP.convertFrom(PaymentStatus::valueOf),
            T_PAYMENT.GATEWAY_PAYMENT_CODE,
            T_PAYMENT.RECORDED_AT,
            T_PAYMENT.LOCK_NO)
        .from(T_PAYMENT);
  }

  /** 列の値から決済記録を復元する。識別子の空文字は、採番されなかったことを表す。 */
  private static Payment restore(
      final PaymentId id,
      final OrderId orderId,
      final Money amount,
      final PaymentStatus status,
      final String gatewayPaymentCode,
      final Instant recordedAt,
      final long lockNo) {
    final @Nullable GatewayPaymentCode code =
        gatewayPaymentCode.isEmpty() ? null : new GatewayPaymentCode(gatewayPaymentCode);
    return Payment.restore(
        id, orderId, amount, new ChargeOutcome(status, code), recordedAt, lockNo);
  }

  /** 識別子がなければ空文字を書く（docs/database/postgresql-data-types.md の「文字列の既定値」）。 */
  private static String codeValue(final @Nullable GatewayPaymentCode code) {
    return code == null ? "" : code.value();
  }
}
