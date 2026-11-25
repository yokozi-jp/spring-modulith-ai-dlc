package com.example.demo.payment.idempotencycheck;

import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.annotation.Propagation;

/**
 * 冪等性の検査の自己検査が使う、わざと違反するリスナー。各リスナーは自分の入力例を兼ねる（{@link ListenerExample}）。
 *
 * <p>全件のテストの文脈に載せないため、自己検査だけが {@code @Import} する。
 */
// 受信メソッドは on と命名する規約のため。
@SuppressWarnings("PMD.ShortMethodName")
/* package */ final class ViolatingProbes {

  /** 届くたびに金額を足す。行の数は変わらず、値が変わる。 */
  /* package */ static class AccumulatingListener implements ListenerExample {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ AccumulatingListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final Accumulate event) {
      ProbePayments.addOne(dsl, event.paymentId());
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new Accumulate(ProbePayments.declinedPayment(dsl, tenant), tenant);
    }

    /**
     * 決済の行を指すイベント。
     *
     * @param paymentId 決済の {@code public_id}
     * @param tenantId イベントのテナント
     */
    /* package */ record Accumulate(UUID paymentId, TenantId tenantId) implements TenantScoped {}
  }

  /** 入力例を持たない。 */
  /* package */ static class UncoveredListener {

    @ApplicationModuleListener
    /* package */ void on(final Uncovered event) {
      // 何もしない。入力例がないことだけを確かめる。
    }

    /**
     * 入力例のないリスナーのイベント。
     *
     * @param tenantId イベントのテナント
     */
    /* package */ record Uncovered(TenantId tenantId) implements TenantScoped {}
  }

  /** 文脈にないリスナーを指す入力例。 */
  /* package */ static class OrphanExample implements ListenerExample {

    /** 文脈にないリスナーの ID。 */
    /* package */ static final ListenerId MISSING =
        new ListenerId(ViolatingProbes.class.getName() + ".Missing.on(java.lang.Object)");

    @Override
    public ListenerId listener() {
      return MISSING;
    }

    @Override
    public Object prepare(final TenantId tenant) {
      throw new UnsupportedOperationException("an orphan example has no listener to deliver to");
    }
  }

  /** 何も書かない。 */
  /* package */ static class NoopListener implements ListenerExample {

    @ApplicationModuleListener
    /* package */ void on(final Noop event) {
      // 何もしない。1 回目の配信が何も変えないことを確かめる。
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new Noop(tenant);
    }

    /**
     * 何も書かないリスナーのイベント。
     *
     * @param tenantId イベントのテナント
     */
    /* package */ record Noop(TenantId tenantId) implements TenantScoped {}
  }

  /** トランザクションの外で動く。コミットの直前で止められず、同時の 2 回の競合を作れない。 */
  /* package */ static class NonTransactionalListener implements ListenerExample {

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    /* package */ void on(final NonTransactional event) {
      // 何もしない。門がトランザクションの外の配信を止めずに見逃さないことを確かめる。
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new NonTransactional(tenant);
    }

    /**
     * トランザクションの外で動くリスナーのイベント。
     *
     * @param tenantId イベントのテナント
     */
    /* package */ record NonTransactional(TenantId tenantId) implements TenantScoped {}
  }

  /** 衝突を処理せずに INSERT する。2 回目は一意制約で例外になり、出版が FAILED に残る。 */
  /* package */ static class PlainInsertListener implements ListenerExample {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ PlainInsertListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final PlainInsert event) {
      ProbePayments.insertPaid(dsl, event.orderId());
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new PlainInsert(UUID.randomUUID(), tenant);
    }

    /**
     * 注文を指すイベント。
     *
     * @param orderId 注文の ID
     * @param tenantId イベントのテナント
     */
    /* package */ record PlainInsert(UUID orderId, TenantId tenantId) implements TenantScoped {}
  }

  /** ロックを取らない SELECT で状態を見てから書く。同時の 2 回目は 1 回目の結果を見ずに、もう一度足す。 */
  /* package */ static class UnlockedSelectListener implements ListenerExample {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ UnlockedSelectListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final UnlockedSelect event) {
      ProbePayments.addOneIfDeclined(dsl, event.paymentId(), select -> select);
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new UnlockedSelect(ProbePayments.declinedPayment(dsl, tenant), tenant);
    }

    /**
     * 決済の行を指すイベント。
     *
     * @param paymentId 決済の {@code public_id}
     * @param tenantId イベントのテナント
     */
    /* package */ record UnlockedSelect(UUID paymentId, TenantId tenantId)
        implements TenantScoped {}
  }

  /** 届くたびに別の金額で外部システムを呼ぶ。行は INSERT ... ON CONFLICT DO NOTHING で変えない。 */
  /* package */ static class ChangingCallListener implements ListenerExample {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /** 外部の決済代行。 */
    private final PaymentGateway gateway;

    /** 呼んだ回数。金額に足す。 */
    private final AtomicInteger calls = new AtomicInteger();

    /* package */ ChangingCallListener(final DSLContext dsl, final PaymentGateway gateway) {
      this.dsl = dsl;
      this.gateway = gateway;
    }

    @ApplicationModuleListener
    /* package */ void on(final ChangingCall event) {
      gateway.charge(
          new OrderId(event.orderId()),
          new Money(BigDecimal.valueOf(100 + calls.incrementAndGet())));
      ProbePayments.insertPaidIfAbsent(dsl, event.orderId());
    }

    @Override
    public ListenerId listener() {
      return ListenerId.forListener(getClass());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new ChangingCall(UUID.randomUUID(), tenant);
    }

    /**
     * 注文を指すイベント。
     *
     * @param orderId 注文の ID
     * @param tenantId イベントのテナント
     */
    /* package */ record ChangingCall(UUID orderId, TenantId tenantId) implements TenantScoped {}
  }
}
