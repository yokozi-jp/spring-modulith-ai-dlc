package com.example.demo.payment.idempotencycheck;

import static com.example.demo.jooq.payment.Tables.T_PAYMENT;

import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * 冪等性の検査の自己検査が使う、わざと違反するリスナーと通るべきリスナー、その入力例。
 *
 * <p>リスナーは本番のリスナーと同じく {@code payment} のパッケージの {@code *Listener} に置き、トランザクションの名前から {@code payment}
 * のロールで、イベントのテナントで動く。書く表は {@code payment.t_payment} である。全件のテストの文脈に載せないため、{@link Config} は自己検査だけが
 * {@code @Import} する。
 */
// 受信メソッドは on と命名する規約のため。自己検査のリスナーと入力例を 1 か所に集めるため、型が多い。
// 入れ子の型だけを使わせ、static のメソッドは入れ子の型の共通の処理である。
@SuppressWarnings({
  "PMD.ShortMethodName",
  "PMD.CouplingBetweenObjects",
  "PMD.MissingStaticMethodInNonInstantiatableClass"
})
/* package */ final class IdempotencyProbes {

  /** 行の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-06T01:02:03.123456Z");

  /** 前提の決済の状態。リスナーはこれを {@link #PAID} に変える。 */
  private static final String DECLINED = "DECLINED";

  /** リスナーが変えた後の決済の状態。 */
  private static final String PAID = "PAID";

  /** 行を持つモジュール。 */
  private static final String PAYMENT = AsModule.moduleOf(T_PAYMENT);

  private IdempotencyProbes() {}

  /** 決済の行を入れる。 */
  private static void insertPayment(
      final DSLContext tx, final UUID publicId, final UUID orderId, final String status) {
    TestCommonColumns.runAs(() ->
        tx.insertInto(T_PAYMENT)
            .set(T_PAYMENT.PUBLIC_ID, publicId)
            .set(T_PAYMENT.ORDER_PUBLIC_ID, orderId)
            .set(T_PAYMENT.CHARGED_AMOUNT_JPY, new BigDecimal("100.00"))
            .set(T_PAYMENT.PAYMENT_STATUS_TYP, status)
            .set(T_PAYMENT.GATEWAY_PAYMENT_CODE, "probe")
            .set(T_PAYMENT.RECORDED_AT, T0)
            .set(TestCommonColumns.at(T0).forInsert(T_PAYMENT))
            .execute());
  }

  /** 決済の行を INSERT ... ON CONFLICT DO NOTHING で入れる。 */
  private static void insertPaymentIfAbsent(final DSLContext tx, final UUID orderId) {
    TestCommonColumns.runAs(() ->
        tx.insertInto(T_PAYMENT)
            .set(T_PAYMENT.PUBLIC_ID, UUID.randomUUID())
            .set(T_PAYMENT.ORDER_PUBLIC_ID, orderId)
            .set(T_PAYMENT.CHARGED_AMOUNT_JPY, new BigDecimal("100.00"))
            .set(T_PAYMENT.PAYMENT_STATUS_TYP, PAID)
            .set(T_PAYMENT.GATEWAY_PAYMENT_CODE, "probe")
            .set(T_PAYMENT.RECORDED_AT, T0)
            .set(TestCommonColumns.at(T0).forInsert(T_PAYMENT))
            .onConflictDoNothing()
            .execute());
  }

  /** テナントに状態 {@link #DECLINED} の決済の行を入れ、その ID を返す。 */
  private static UUID declinedPayment(final DSLContext dsl, final TenantId tenant) {
    final UUID publicId = UUID.randomUUID();
    AsModule.run(
        dsl, PAYMENT, tenant, tx -> insertPayment(tx, publicId, UUID.randomUUID(), DECLINED));
    return publicId;
  }

  /** 決済の金額に 1 を足す。 */
  private static void addOne(final DSLContext dsl, final UUID paymentId) {
    dsl.update(T_PAYMENT)
        .set(T_PAYMENT.CHARGED_AMOUNT_JPY, T_PAYMENT.CHARGED_AMOUNT_JPY.plus(BigDecimal.ONE))
        .set(T_PAYMENT.PAYMENT_STATUS_TYP, PAID)
        .where(T_PAYMENT.PUBLIC_ID.eq(paymentId))
        .execute();
  }

  /**
   * 決済の行を指すイベント。
   *
   * @param paymentId 決済の {@code public_id}
   * @param tenantId イベントのテナント
   */
  /* package */ record Accumulate(UUID paymentId, TenantId tenantId) implements TenantScoped {}

  /** 届くたびに金額を足す。行の数は変わらず、値が変わる。 */
  /* package */ static class AccumulatingListener {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ AccumulatingListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final Accumulate event) {
      addOne(dsl, event.paymentId());
    }
  }

  /**
   * 入力例のないリスナーのイベント。
   *
   * @param tenantId イベントのテナント
   */
  /* package */ record Uncovered(TenantId tenantId) implements TenantScoped {}

  /** 入力例を持たない。 */
  /* package */ static class UncoveredListener {

    @ApplicationModuleListener
    /* package */ void on(final Uncovered event) {
      // 何もしない。入力例がないことだけを確かめる。
    }
  }

  /**
   * 何も書かないリスナーのイベント。
   *
   * @param tenantId イベントのテナント
   */
  /* package */ record Noop(TenantId tenantId) implements TenantScoped {}

  /** 何も書かない。 */
  /* package */ static class NoopListener {

    @ApplicationModuleListener
    /* package */ void on(final Noop event) {
      // 何もしない。1 回目の配信が何も変えないことを確かめる。
    }
  }

  /**
   * 注文を指すイベント。
   *
   * @param orderId 注文の ID
   * @param tenantId イベントのテナント
   */
  /* package */ record PlainInsert(UUID orderId, TenantId tenantId) implements TenantScoped {}

  /** 衝突を処理せずに INSERT する。2 回目は一意制約で例外になり、出版が FAILED に残る。 */
  /* package */ static class PlainInsertListener {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ PlainInsertListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final PlainInsert event) {
      insertPayment(dsl, UUID.randomUUID(), event.orderId(), PAID);
    }
  }

  /**
   * 決済の行を指すイベント。
   *
   * @param paymentId 決済の {@code public_id}
   * @param tenantId イベントのテナント
   */
  /* package */ record UnlockedSelect(UUID paymentId, TenantId tenantId) implements TenantScoped {}

  /** ロックを取らない SELECT で状態を見てから書く。同時の 2 回目は 1 回目の結果を見ずに、もう一度足す。 */
  /* package */ static class UnlockedSelectListener {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ UnlockedSelectListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final UnlockedSelect event) {
      final String status =
          dsl.select(T_PAYMENT.PAYMENT_STATUS_TYP)
              .from(T_PAYMENT)
              .where(T_PAYMENT.PUBLIC_ID.eq(event.paymentId()))
              .fetchSingle(T_PAYMENT.PAYMENT_STATUS_TYP);
      if (DECLINED.equals(status)) {
        addOne(dsl, event.paymentId());
      }
    }
  }

  /**
   * 注文を指すイベント。
   *
   * @param orderId 注文の ID
   * @param tenantId イベントのテナント
   */
  /* package */ record ChangingCall(UUID orderId, TenantId tenantId) implements TenantScoped {}

  /** 届くたびに別の金額で外部システムを呼ぶ。行は INSERT ... ON CONFLICT DO NOTHING で変えない。 */
  /* package */ static class ChangingCallListener {

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
      insertPaymentIfAbsent(dsl, event.orderId());
    }
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

  /** 状態の遷移を {@code lock_no} の WHERE に書いて UPDATE する。2 回目は 0 件になる。 */
  /* package */ static class LockNoUpdateListener {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ LockNoUpdateListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final LockNoUpdate event) {
      dsl.update(T_PAYMENT)
          .set(T_PAYMENT.CHARGED_AMOUNT_JPY, T_PAYMENT.CHARGED_AMOUNT_JPY.plus(BigDecimal.ONE))
          .set(T_PAYMENT.PAYMENT_STATUS_TYP, PAID)
          .set(T_PAYMENT.LOCK_NO, T_PAYMENT.LOCK_NO.plus(1))
          .where(T_PAYMENT.PUBLIC_ID.eq(event.paymentId()))
          .and(T_PAYMENT.LOCK_NO.eq(event.lockNo()))
          .execute();
    }
  }

  /**
   * 決済の行を指すイベント。
   *
   * @param paymentId 決済の {@code public_id}
   * @param tenantId イベントのテナント
   */
  /* package */ record ForUpdate(UUID paymentId, TenantId tenantId) implements TenantScoped {}

  /** {@code SELECT ... FOR UPDATE} で状態を見てから書く。同時の 2 回目は 1 回目のコミットを待って、変わった状態を見る。 */
  /* package */ static class ForUpdateListener {

    /** 行を読み書きする jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ ForUpdateListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final ForUpdate event) {
      final String status =
          dsl.select(T_PAYMENT.PAYMENT_STATUS_TYP)
              .from(T_PAYMENT)
              .where(T_PAYMENT.PUBLIC_ID.eq(event.paymentId()))
              .forUpdate()
              .fetchSingle(T_PAYMENT.PAYMENT_STATUS_TYP);
      if (DECLINED.equals(status)) {
        addOne(dsl, event.paymentId());
      }
    }
  }

  /**
   * 注文を指すイベント。
   *
   * @param orderId 注文の ID
   * @param tenantId イベントのテナント
   */
  /* package */ record OnConflict(UUID orderId, TenantId tenantId) implements TenantScoped {}

  /** INSERT ... ON CONFLICT DO NOTHING で入れる。2 回目は何も入れない。 */
  /* package */ static class OnConflictListener {

    /** 行を書く jOOQ のコンテキスト。 */
    private final DSLContext dsl;

    /* package */ OnConflictListener(final DSLContext dsl) {
      this.dsl = dsl;
    }

    @ApplicationModuleListener
    /* package */ void on(final OnConflict event) {
      insertPaymentIfAbsent(dsl, event.orderId());
    }
  }

  /**
   * 準備の関数で作る入力例。
   *
   * @param listener 入力例を届けるリスナー
   * @param preparation テナントに前提を作り、イベントを返す
   */
  /* package */ record Example(ListenerId listener, Function<TenantId, Object> preparation)
      implements ListenerExample {

    @Override
    public Object prepare(final TenantId tenant) {
      return preparation.apply(tenant);
    }
  }

  /** 自己検査だけが {@code @Import} する、リスナーと入力例の構成。 */
  // リスナーと入力例を 1 つずつ Bean にするため、メソッドが多い。
  @SuppressWarnings("PMD.TooManyMethods")
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class Config {

    /** リスナーのない入力例が指す ID。 */
    /* package */ static final ListenerId MISSING =
        new ListenerId(IdempotencyProbes.class.getName() + ".Missing.on(java.lang.Object)");

    @Bean
    /* package */ AccumulatingListener accumulatingListener(final DSLContext dsl) {
      return new AccumulatingListener(dsl);
    }

    @Bean
    /* package */ Example accumulatingExample(final DSLContext dsl) {
      return new Example(
          ListenerId.forListener(AccumulatingListener.class),
          tenant -> new Accumulate(declinedPayment(dsl, tenant), tenant));
    }

    @Bean
    /* package */ UncoveredListener uncoveredListener() {
      return new UncoveredListener();
    }

    @Bean
    /* package */ Example orphanExample() {
      return new Example(MISSING, Uncovered::new);
    }

    @Bean
    /* package */ NoopListener noopListener() {
      return new NoopListener();
    }

    @Bean
    /* package */ Example noopExample() {
      return new Example(ListenerId.forListener(NoopListener.class), Noop::new);
    }

    @Bean
    /* package */ PlainInsertListener plainInsertListener(final DSLContext dsl) {
      return new PlainInsertListener(dsl);
    }

    @Bean
    /* package */ Example plainInsertExample() {
      return new Example(
          ListenerId.forListener(PlainInsertListener.class),
          tenant -> new PlainInsert(UUID.randomUUID(), tenant));
    }

    @Bean
    /* package */ UnlockedSelectListener unlockedSelectListener(final DSLContext dsl) {
      return new UnlockedSelectListener(dsl);
    }

    @Bean
    /* package */ Example unlockedSelectExample(final DSLContext dsl) {
      return new Example(
          ListenerId.forListener(UnlockedSelectListener.class),
          tenant -> new UnlockedSelect(declinedPayment(dsl, tenant), tenant));
    }

    @Bean
    /* package */ ChangingCallListener changingCallListener(
        final DSLContext dsl, final PaymentGateway gateway) {
      return new ChangingCallListener(dsl, gateway);
    }

    @Bean
    /* package */ Example changingCallExample() {
      return new Example(
          ListenerId.forListener(ChangingCallListener.class),
          tenant -> new ChangingCall(UUID.randomUUID(), tenant));
    }

    @Bean
    /* package */ LockNoUpdateListener lockNoUpdateListener(final DSLContext dsl) {
      return new LockNoUpdateListener(dsl);
    }

    @Bean
    /* package */ Example lockNoUpdateExample(final DSLContext dsl) {
      return new Example(
          ListenerId.forListener(LockNoUpdateListener.class),
          tenant -> new LockNoUpdate(declinedPayment(dsl, tenant), 1L, tenant));
    }

    @Bean
    /* package */ ForUpdateListener forUpdateListener(final DSLContext dsl) {
      return new ForUpdateListener(dsl);
    }

    @Bean
    /* package */ Example forUpdateExample(final DSLContext dsl) {
      return new Example(
          ListenerId.forListener(ForUpdateListener.class),
          tenant -> new ForUpdate(declinedPayment(dsl, tenant), tenant));
    }

    @Bean
    /* package */ OnConflictListener onConflictListener(final DSLContext dsl) {
      return new OnConflictListener(dsl);
    }

    @Bean
    /* package */ Example onConflictExample() {
      return new Example(
          ListenerId.forListener(OnConflictListener.class),
          tenant -> new OnConflict(UUID.randomUUID(), tenant));
    }
  }
}
