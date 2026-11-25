package com.example.demo.payment.idempotencycheck;

import static com.example.demo.jooq.payment.Tables.T_PAYMENT;

import com.example.demo.payment.domain.model.PaymentStatus;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;
import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * 冪等性の検査の自己検査が使う、重複を DB が直列にする操作で判定し、検査を通るべきリスナー。各リスナーは自分の入力例を兼ねる（{@link ListenerExample}）。
 *
 * <p>全件のテストの文脈に載せないため、自己検査だけが {@code @Import} する。
 */
// 受信メソッドは on と命名する規約のため。
@SuppressWarnings("PMD.ShortMethodName")
/* package */ final class SerializingProbes {

  /** 状態の遷移を {@code lock_no} の WHERE に書いて UPDATE する。2 回目は 0 件になる。 */
  /* package */ static class LockNoUpdateListener implements ListenerExample {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ LockNoUpdateListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final LockNoUpdate event) {
      dsl.update(T_PAYMENT)
          .set(T_PAYMENT.CHARGED_AMOUNT_JPY, T_PAYMENT.CHARGED_AMOUNT_JPY.plus(BigDecimal.ONE))
          .set(T_PAYMENT.PAYMENT_STATUS_TYP, PaymentStatus.PAID.name())
          .set(T_PAYMENT.LOCK_NO, T_PAYMENT.LOCK_NO.plus(1))
          .where(T_PAYMENT.PUBLIC_ID.eq(event.paymentId()))
          .and(T_PAYMENT.LOCK_NO.eq(event.lockNo()))
          .execute();
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new LockNoUpdate(ProbePayments.declinedPayment(dsl, tenant), 1L, tenant);
    }

    /**
     * 決済の行と、読んだときのロック番号を指すイベント。
     *
     * @param paymentId 決済の {@code public_id}
     * @param lockNo 決済を読んだときの {@code lock_no}
     * @param tenantId イベントのテナント
     */
    /* package */ record LockNoUpdate(UUID paymentId, long lockNo, TenantId tenantId)
        implements TenantScoped {}
  }

  /** {@code SELECT ... FOR UPDATE} で状態を見てから書く。同時の 2 回目は 1 回目のコミットを待って、変わった状態を見る。 */
  /* package */ static class ForUpdateListener implements ListenerExample {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ ForUpdateListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final ForUpdate event) {
      ProbePayments.addOneIfDeclined(dsl, event.paymentId(), select -> select.forUpdate());
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new ForUpdate(ProbePayments.declinedPayment(dsl, tenant), tenant);
    }

    /**
     * 決済の行を指すイベント。
     *
     * @param paymentId 決済の {@code public_id}
     * @param tenantId イベントのテナント
     */
    /* package */ record ForUpdate(UUID paymentId, TenantId tenantId) implements TenantScoped {}
  }

  /** INSERT ... ON CONFLICT DO NOTHING で入れる。2 回目は何も入れない。 */
  /* package */ static class OnConflictListener implements ListenerExample {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ OnConflictListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final OnConflict event) {
      ProbePayments.insertPaidIfAbsent(dsl, event.orderId());
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new OnConflict(UUID.randomUUID(), tenant);
    }

    /**
     * 注文を指すイベント。
     *
     * @param orderId 注文の ID
     * @param tenantId イベントのテナント
     */
    /* package */ record OnConflict(UUID orderId, TenantId tenantId) implements TenantScoped {}
  }
}
