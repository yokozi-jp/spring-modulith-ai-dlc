package com.example.demo.payment.infrastructure.persistence;

import static com.example.demo.jooq.payment.Tables.T_PAYMENT;

import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentId;
import com.example.demo.payment.domain.model.PaymentRepository;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import java.time.Instant;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record6;
import org.jooq.Records;
import org.jooq.SelectJoinStep;
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
        .fetchOptional(Records.mapping(Payment::restore));
  }

  @Override
  public void add(final Payment payment) {
    // ユニークインデックスは public_id（UUID v4 で衝突しない）と order_public_id だけなので、409 は注文の決済記録の重複である。
    tableWriter.insert(
        dsl.insertInto(T_PAYMENT)
            .set(T_PAYMENT.PUBLIC_ID, payment.id().value())
            .set(T_PAYMENT.ORDER_PUBLIC_ID, payment.orderId().value())
            .set(T_PAYMENT.CHARGED_AMOUNT_JPY, payment.amount().amount())
            .set(T_PAYMENT.GATEWAY_PAYMENT_CODE, payment.gatewayPaymentCode().value())
            .set(T_PAYMENT.PAID_AT, payment.paidAt())
            .set(commonColumns.forInsert(T_PAYMENT)));
  }

  /** 決済記録の列を、Payment.restore の引数の型と順に選ぶ。 */
  private SelectJoinStep<Record6<PaymentId, OrderId, Money, GatewayPaymentCode, Instant, Long>>
      selectPayments() {
    return dsl.select(
            T_PAYMENT.PUBLIC_ID.convertFrom(PaymentId::new),
            T_PAYMENT.ORDER_PUBLIC_ID.convertFrom(OrderId::new),
            T_PAYMENT.CHARGED_AMOUNT_JPY.convertFrom(Money::new),
            T_PAYMENT.GATEWAY_PAYMENT_CODE.convertFrom(GatewayPaymentCode::new),
            T_PAYMENT.PAID_AT,
            T_PAYMENT.LOCK_NO)
        .from(T_PAYMENT);
  }
}
