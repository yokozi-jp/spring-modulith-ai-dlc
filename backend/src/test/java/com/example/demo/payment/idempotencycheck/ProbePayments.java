package com.example.demo.payment.idempotencycheck;

import static com.example.demo.jooq.payment.Tables.T_PAYMENT;

import com.example.demo.jooq.payment.tables.records.TPaymentRecord;
import com.example.demo.payment.domain.model.PaymentStatus;
import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.testkit.AsModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.InsertSetMoreStep;
import org.jooq.Record1;
import org.jooq.ResultQuery;
import org.jooq.SelectConditionStep;

/**
 * 自己検査のリスナーと入力例が読み書きする {@code payment.t_payment} の行。
 *
 * <p>リスナーは {@code payment} のパッケージに置くため、トランザクションの名前から {@code payment} のロールで、イベントのテナントで動く。
 */
/* package */ final class ProbePayments {

  /** 行の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-06T01:02:03.123456Z");

  /** 前提の決済の状態。リスナーはこれを {@link PaymentStatus#PAID} に変える。 */
  private static final String DECLINED = PaymentStatus.DECLINED.name();

  /** リスナーが変えた後の決済の状態。 */
  private static final String PAID = PaymentStatus.PAID.name();

  private ProbePayments() {}

  /** テナントに状態 {@code DECLINED} の決済の行を入れ、その ID を返す。 */
  /* package */ static UUID declinedPayment(final DSLContext dsl, final TenantId tenant) {
    final UUID publicId = UUID.randomUUID();
    AsModule.run(
        dsl,
        AsModule.moduleOf(T_PAYMENT),
        tenant,
        tx ->
            TestCommonColumns.runAs(
                () -> values(tx, publicId, UUID.randomUUID(), DECLINED).execute()));
    return publicId;
  }

  /** 注文の決済の行を、状態 {@code PAID} で入れる。一意索引の衝突は例外になる。 */
  /* package */ static void insertPaid(final DSLContext dsl, final UUID orderId) {
    TestCommonColumns.runAs(() -> values(dsl, UUID.randomUUID(), orderId, PAID).execute());
  }

  /** 注文の決済の行を、状態 {@code PAID} で {@code INSERT ... ON CONFLICT DO NOTHING} で入れる。 */
  /* package */ static void insertPaidIfAbsent(final DSLContext dsl, final UUID orderId) {
    TestCommonColumns.runAs(
        () -> values(dsl, UUID.randomUUID(), orderId, PAID).onConflictDoNothing().execute());
  }

  /** 決済の金額に 1 を足し、状態を {@code PAID} にする。 */
  /* package */ static void addOne(final DSLContext dsl, final UUID paymentId) {
    dsl.update(T_PAYMENT)
        .set(T_PAYMENT.CHARGED_AMOUNT_JPY, T_PAYMENT.CHARGED_AMOUNT_JPY.plus(BigDecimal.ONE))
        .set(T_PAYMENT.PAYMENT_STATUS_TYP, PAID)
        .where(T_PAYMENT.PUBLIC_ID.eq(paymentId))
        .execute();
  }

  /**
   * 決済の状態を読み、{@code DECLINED} なら {@link #addOne} する。
   *
   * @param lock 状態を読む SELECT に、ロックを付けるか（{@code SELECT ... FOR UPDATE}）を決める
   */
  /* package */ static void addOneIfDeclined(
      final DSLContext dsl,
      final UUID paymentId,
      final Function<SelectConditionStep<Record1<String>>, ResultQuery<Record1<String>>> lock) {
    final String status =
        lock.apply(
                dsl.select(T_PAYMENT.PAYMENT_STATUS_TYP)
                    .from(T_PAYMENT)
                    .where(T_PAYMENT.PUBLIC_ID.eq(paymentId)))
            .fetchSingle(T_PAYMENT.PAYMENT_STATUS_TYP);
    if (DECLINED.equals(status)) {
      addOne(dsl, paymentId);
    }
  }

  private static InsertSetMoreStep<TPaymentRecord> values(
      final DSLContext tx, final UUID publicId, final UUID orderId, final String status) {
    return tx.insertInto(T_PAYMENT)
        .set(T_PAYMENT.PUBLIC_ID, publicId)
        .set(T_PAYMENT.ORDER_PUBLIC_ID, orderId)
        .set(T_PAYMENT.CHARGED_AMOUNT_JPY, new BigDecimal("100.00"))
        .set(T_PAYMENT.PAYMENT_STATUS_TYP, status)
        .set(T_PAYMENT.GATEWAY_PAYMENT_CODE, "probe")
        .set(T_PAYMENT.RECORDED_AT, T0)
        .set(TestCommonColumns.at(T0).forInsert(T_PAYMENT));
  }
}
