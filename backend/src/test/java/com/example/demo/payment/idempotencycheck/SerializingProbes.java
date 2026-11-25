package com.example.demo.payment.idempotencycheck;

import static com.example.demo.jooq.payment.Tables.T_PAYMENT;

import com.example.demo.jooq.payment.tables.records.TPaymentRecord;
import com.example.demo.payment.domain.model.PaymentStatus;
import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.Versioned;
import com.example.demo.shared.persistence.AggregateTable;
import com.example.demo.shared.persistence.TableWriter;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import com.example.demo.testkit.idempotency.ListenerExample;
import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * 冪等性の検査の自己検査が使う、重複を DB が直列にする操作で判定し、検査を通るべきリスナー。各リスナーは自分の入力例を兼ねる（{@link ListenerExample}）。
 *
 * <p>全件のテストの文脈に載せないため、自己検査だけが {@code @Import} する。
 */
// 受信メソッドは on と命名する規約のため。
@SuppressWarnings("PMD.ShortMethodName")
/* package */ final class SerializingProbes {

  /**
   * 決済をロックを取らずに読み、読んだ版を WHERE に書く {@link TableWriter#update} で状態を遷移させる。
   *
   * <p>同時の 2 回目は、1 回目の行ロックを待ってから 0 件の更新になり、{@link TableWriter#update} が {@code VERSION} の {@link
   * ConflictException} を投げる。その例外だけを catch して読み直し、遷移が済んでいれば処理済みとして終える。0 件の更新は SQL
   * の失敗でないため、トランザクションは続けられる。
   */
  /* package */ static class LockNoUpdateListener implements ListenerProbe {

    /** 決済の集約。版を比べるのに要る列だけを宣言する。 */
    private static final AggregateTable<TPaymentRecord, UUID, PaymentVersion> PAYMENT =
        AggregateTable.of(
            T_PAYMENT, T_PAYMENT.PUBLIC_ID, PaymentVersion::paymentId, T_PAYMENT.PAYMENT_ID);

    /** 行を読み、前提を作る jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /** 集約を版で更新する shared の入口。 */
    private final TableWriter tableWriter;

    /* package */ LockNoUpdateListener(final DSLContext dsl, final TableWriter tableWriter) {
      this.dsl = dsl;
      this.tableWriter = tableWriter;
    }

    /**
     * 決済を {@code PAID} にする。済んでいれば何もしない。
     *
     * @param event 決済の行を指すイベント
     */
    // PgmCdAspect が TableWriter の書く *_pgm_cd を束縛するのは public のメソッドだけのため、本番の Listener と同じく public
    // にする。
    @SuppressWarnings("PMD.PublicMemberInNonPublicType")
    @ApplicationModuleListener
    public void on(final LockNoUpdate event) {
      final PaymentVersion read = read(event.paymentId());
      if (read.paid()) {
        return;
      }
      try {
        tableWriter.update(
            PAYMENT,
            read,
            set ->
                set.set(
                        T_PAYMENT.CHARGED_AMOUNT_JPY,
                        T_PAYMENT.CHARGED_AMOUNT_JPY.plus(BigDecimal.ONE))
                    .set(T_PAYMENT.PAYMENT_STATUS_TYP, PaymentStatus.PAID.name()));
      } catch (ConflictException e) {
        if (e.kind() != ConflictException.Kind.VERSION || !read(event.paymentId()).paid()) {
          throw e;
        }
      }
    }

    private PaymentVersion read(final UUID paymentId) {
      return dsl.select(T_PAYMENT.PUBLIC_ID, T_PAYMENT.PAYMENT_STATUS_TYP, T_PAYMENT.LOCK_NO)
          .from(T_PAYMENT)
          .where(T_PAYMENT.PUBLIC_ID.eq(paymentId))
          .fetchSingle(Records.mapping(PaymentVersion::new));
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new LockNoUpdate(ProbePayments.declinedPayment(dsl, tenant), tenant);
    }

    /**
     * 読んだ決済の状態と版。
     *
     * @param paymentId 決済の {@code public_id}
     * @param status 決済の状態
     * @param lockNo 読んだときの {@code lock_no}
     */
    /* package */ record PaymentVersion(UUID paymentId, String status, long lockNo)
        implements Versioned {

      /** 遷移が済んでいるか。 */
      /* package */ boolean paid() {
        return PaymentStatus.PAID.name().equals(status);
      }
    }

    /**
     * 決済の行を指すイベント。
     *
     * @param paymentId 決済の {@code public_id}
     * @param tenantId イベントのテナント
     */
    /* package */ record LockNoUpdate(UUID paymentId, TenantId tenantId) implements TenantScoped {}
  }

  /** {@code SELECT ... FOR UPDATE} で状態を見てから書く。同時の 2 回目は 1 回目のコミットを待って、変わった状態を見る。 */
  /* package */ static class ForUpdateListener implements ListenerProbe {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ ForUpdateListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final ForUpdate event) {
      ProbePayments.addOneIfDeclined(dsl, event.paymentId(), true);
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
  /* package */ static class OnConflictListener implements ListenerProbe {

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
