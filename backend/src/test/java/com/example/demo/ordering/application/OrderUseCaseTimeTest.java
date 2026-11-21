package com.example.demo.ordering.application;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.testkit.AdvancingClock;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.BindTestTenantExtension;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.AssertablePublishedEvents;

/**
 * 注文の下書きと確定が、ユースケースの中で時計を 1 回だけ読み、業務の時刻と共通カラムの時刻をそろえることを検証する（ADR-109）。
 *
 * <p>時計は読むたびに進むものに差し替える。2 回読めば値が違うので、そろわなければ失敗する。
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({SharedTestConfiguration.class, OrderUseCaseTimeTest.ClockConfiguration.class})
@ExtendWith({CleanGeneratedTablesExtension.class, BindTestTenantExtension.class})
class OrderUseCaseTimeTest {

  /** 注文を下書きする CommandHandler。 */
  @Autowired private DraftOrderCommandHandler draftOrder;

  /** 注文を確定する CommandHandler。 */
  @Autowired private ConfirmOrderCommandHandler confirmOrder;

  /** 商品を登録し、注文の行を読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 共通カラムの trace ID を作る observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

  @Test
  @DisplayName("下書きでは、ordered_at、注文の created_at と updated_at、すべての明細の created_at が同じ時刻になる")
  void draftUsesOneTime() {
    final String orderId = drafted();

    final Record order = orderRow(orderId);
    final Instant orderedAt = order.get(T_ORDER.ORDERED_AT);
    assertThat(List.of(order.get(T_ORDER.CREATED_AT), order.get(T_ORDER.UPDATED_AT)))
        .as("orderId=%s の created_at と updated_at", orderId)
        .containsOnly(orderedAt);
    assertThat(lineCreatedAts(orderId))
        .as("orderId=%s の明細の created_at", orderId)
        .hasSize(2)
        .containsOnly(orderedAt);
  }

  @Test
  @DisplayName("確定では、OrderConfirmed の時刻と注文の updated_at が同じ時刻になる")
  void confirmUsesOneTime(final AssertablePublishedEvents events) {
    final String orderId = drafted();

    observed(() -> confirmOrder.handle(new ConfirmOrderCommand(orderId, new ExpectedLockNo(1))));

    final Instant updatedAt = orderRow(orderId).get(T_ORDER.UPDATED_AT);
    assertThat(events.ofType(OrderConfirmed.class).matching(e -> e.orderId().equals(orderId)))
        .as("orderId=%s の OrderConfirmed", orderId)
        .singleElement()
        .extracting(OrderConfirmed::confirmedAt)
        .isEqualTo(updatedAt);
    assertThat(updatedAt)
        .as("orderId=%s の確定の updated_at は下書きの時刻より後", orderId)
        .isAfter(orderRow(orderId).get(T_ORDER.CREATED_AT));
  }

  /** 2 つの商品を 1 行ずつ注文する下書きを作り、注文 ID を返す。 */
  private String drafted() {
    final UUID pen = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    final UUID note = TestProducts.onSale(dsl, UniqueCodes.next("P"), "300.00");
    return observed(() ->
            draftOrder.handle(
                new DraftOrderCommand(
                    UniqueCodes.next("C"),
                    List.of(
                        new DraftOrderCommand.Line(pen.toString(), 1),
                        new DraftOrderCommand.Line(note.toString(), 2)))))
        .orderId();
  }

  private Record orderRow(final String orderId) {
    return AsModule.call(
        dsl,
        "ordering",
        tx ->
            tx.select(T_ORDER.ORDERED_AT, T_ORDER.CREATED_AT, T_ORDER.UPDATED_AT)
                .from(T_ORDER)
                .where(T_ORDER.PUBLIC_ID.eq(UUID.fromString(orderId)))
                .fetchSingle());
  }

  private List<Instant> lineCreatedAts(final String orderId) {
    return AsModule.call(
        dsl,
        "ordering",
        tx ->
            tx.select(T_ORDER_LINE.CREATED_AT)
                .from(T_ORDER_LINE)
                .join(T_ORDER)
                .on(T_ORDER.ORDER_ID.eq(T_ORDER_LINE.ORDER_ID))
                .where(T_ORDER.PUBLIC_ID.eq(UUID.fromString(orderId)))
                .fetch(T_ORDER_LINE.CREATED_AT));
  }

  /** 共通カラムの trace ID を取れるよう、observation の中で処理を呼ぶ。 */
  private <T> T observed(final Supplier<T> action) {
    return Observation.createNotStarted("order-test", observationRegistry).observe(action);
  }

  /** 読むたびに進む時計に差し替える。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class ClockConfiguration {

    @Bean
    @Primary
    /* package */ Clock advancingClock() {
      return new AdvancingClock(Instant.parse("2026-10-05T01:02:03.123456Z"));
    }
  }
}
