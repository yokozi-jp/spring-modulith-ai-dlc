package com.example.demo.payment.application;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.ordering.application.ConfirmOrderCommandHandler;
import com.example.demo.ordering.application.DraftOrderCommandHandler;
import com.example.demo.ordering.domain.model.OrderStatus;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * {@link OrderConfirmedListener} の冪等性の全件のテストの入力例。確定した注文と、その {@link OrderConfirmed} を作る。
 *
 * <p>注文は下書きの CommandHandler で作り、状態だけを表で確定にする。確定の CommandHandler は {@link OrderConfirmed}
 * を発行し、検査の前にリスナーが決済してしまうためである。
 */
public final class OrderConfirmedListenerExample implements ListenerExample {

  /** 確定した時刻。マイクロ秒の精度にする。 */
  private static final Instant CONFIRMED_AT = Instant.parse("2026-10-06T01:02:03.123456Z");

  /** 下書きの注文を作る補助。 */
  private final OrderConfirmedFixture fixture;

  /** 注文の状態を書く jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 注文を作る CommandHandler と、共通カラムの trace ID を作る observation registry を受け取る。 */
  public OrderConfirmedListenerExample(
      final DSLContext dsl,
      final DraftOrderCommandHandler draftOrder,
      final ConfirmOrderCommandHandler confirmOrder,
      final ObservationRegistry observationRegistry) {
    this.fixture = new OrderConfirmedFixture(dsl, draftOrder, confirmOrder, observationRegistry);
    this.dsl = dsl;
  }

  @Override
  public ListenerId listener() {
    return ListenerId.forListener(OrderConfirmedListener.class);
  }

  @Override
  public Object prepare(final TenantId tenant) {
    final String orderId = fixture.draftedOrderId(tenant);
    AsModule.run(
        dsl,
        AsModule.moduleOf(T_ORDER),
        tenant,
        tx ->
            tx.update(T_ORDER)
                .set(T_ORDER.ORDER_STATUS_TYP, OrderStatus.CONFIRMED.name())
                .where(T_ORDER.PUBLIC_ID.eq(UUID.fromString(orderId)))
                .execute());
    return new OrderConfirmed(orderId, CONFIRMED_AT, tenant);
  }
}
