package com.example.demo.payment.idempotencycheck;

import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.shared.events.ResubmissionPolicy;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  /** 最初の配信の失敗で出版を諦める、再投入の方針の名前。自己検査が {@code abandon-after-attempts=1} で定義する。 */
  /* package */ static final String ABANDON_AT_FIRST = "abandon-at-first";

  /** 届くたびに金額を足す。行の数は変わらず、値が変わる。 */
  /* package */ static class AccumulatingListener implements ListenerProbe {

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
  /* package */ static class NoopListener implements ListenerProbe {

    @ApplicationModuleListener
    /* package */ void on(final Noop event) {
      // 何もしない。1 回目の配信が何も変えないことを確かめる。
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
  /* package */ static class NonTransactionalListener implements ListenerProbe {

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    /* package */ void on(final NonTransactional event) {
      // 何もしない。門がトランザクションの外の配信を止めずに見逃さないことを確かめる。
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
  /* package */ static class PlainInsertListener implements ListenerProbe {

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

  /**
   * {@link PlainInsertListener} と同じく衝突を処理せずに INSERT し、方針 {@link #ABANDON_AT_FIRST} で最初の失敗の出版を諦める。2
   * 回目の出版は {@code FAILED} に残らず、{@code ABANDONED} で archive へ移る。
   */
  /* package */ static class AbandoningInsertListener implements ListenerProbe {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ AbandoningInsertListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    @ResubmissionPolicy(ABANDON_AT_FIRST)
    /* package */ void on(final AbandoningInsert event) {
      ProbePayments.insertPaid(dsl, event.orderId());
    }

    @Override
    public Object prepare(final TenantId tenant) {
      return new AbandoningInsert(UUID.randomUUID(), tenant);
    }

    /**
     * 注文を指すイベント。
     *
     * @param orderId 注文の ID
     * @param tenantId イベントのテナント
     */
    /* package */ record AbandoningInsert(UUID orderId, TenantId tenantId)
        implements TenantScoped {}
  }

  /** 1 回目は何もせず、2 回目の配信がロックを取る前に例外で終わる。 */
  /* package */ static class FailingSecondListener implements ListenerProbe {

    /** 前提を作ってから届いた配信の数。 */
    private final AtomicInteger deliveries = new AtomicInteger();

    @ApplicationModuleListener
    /* package */ void on(final FailingSecond event) {
      if (deliveries.getAndIncrement() > 0) {
        throw new IllegalStateException("the second delivery fails: " + event);
      }
    }

    @Override
    public Object prepare(final TenantId tenant) {
      deliveries.set(0);
      return new FailingSecond(tenant);
    }

    /**
     * 2 回目に失敗するリスナーのイベント。
     *
     * @param tenantId イベントのテナント
     */
    /* package */ record FailingSecond(TenantId tenantId) implements TenantScoped {}
  }

  /**
   * 1 回目は何もせず、2 回目の配信が、ロックを取る前に {@link #resume} まで待つ。2 回目はロックも門にも届かず、同時の 2 回の競合を作れない。
   *
   * <p>テストは、検査の後に {@link #resume} を呼んで 2 回目を通す。
   */
  /* package */ static class StalledSecondListener implements ListenerProbe {

    /**
     * 2 回目を止める上限。競合を待つ上限（3 秒）より長くし、止めたトランザクションを切る {@code idle_in_transaction_session_timeout}（10
     * 秒）より短くする。
     */
    private static final Duration STALL_LIMIT = Duration.ofSeconds(6);

    /** 前提を作ってから届いた配信の数。 */
    private final AtomicInteger deliveries = new AtomicInteger();

    /** 止めた 2 回目を通す門。前提を作るたびに閉じ直す。 */
    private final AtomicReference<CountDownLatch> resumed =
        new AtomicReference<>(new CountDownLatch(0));

    @ApplicationModuleListener
    /* package */ void on(final StalledSecond event) throws InterruptedException {
      if (deliveries.getAndIncrement() > 0
          && !resumed.get().await(STALL_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the stalled delivery was not resumed: " + event);
      }
    }

    @Override
    public Object prepare(final TenantId tenant) {
      deliveries.set(0);
      resumed.set(new CountDownLatch(1));
      return new StalledSecond(tenant);
    }

    /** 止めている 2 回目を通す。 */
    /* package */ void resume() {
      resumed.get().countDown();
    }

    /**
     * 2 回目が止まるリスナーのイベント。
     *
     * @param tenantId イベントのテナント
     */
    /* package */ record StalledSecond(TenantId tenantId) implements TenantScoped {}
  }

  /** ロックを取らない SELECT で状態を見てから書く。同時の 2 回目は 1 回目の結果を見ずに、もう一度足す。 */
  /* package */ static class UnlockedSelectListener implements ListenerProbe {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ UnlockedSelectListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final UnlockedSelect event) {
      ProbePayments.addOneIfDeclined(dsl, event.paymentId(), false);
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
  /* package */ static class ChangingCallListener implements ListenerProbe {

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
