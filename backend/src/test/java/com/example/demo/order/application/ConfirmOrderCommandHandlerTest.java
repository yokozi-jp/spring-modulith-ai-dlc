package com.example.demo.order.application;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.order.OrderConfirmed;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.AssertablePublishedEvents;
import org.springframework.modulith.test.Scenario;

/**
 * 注文の確定が {@link OrderConfirmed} を発行し、イベント出版レジストリに記録することを検証する。
 *
 * <p>イベント出版レジストリは、イベントを受けるリスナーがあるときだけ出版を記録する。ステップ 1 には本番のリスナーがないため、テストのリスナーを登録する。
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({SharedTestConfiguration.class, ConfirmOrderCommandHandlerTest.ProbeConfiguration.class})
@ExtendWith(CleanGeneratedTablesExtension.class)
class ConfirmOrderCommandHandlerTest {

  /** テスト対象の CommandHandler。 */
  @Autowired private ConfirmOrderCommandHandler confirmOrder;

  /** 確定する注文を作る CommandHandler。 */
  @Autowired private DraftOrderCommandHandler draftOrder;

  /** 422 の準備に注文を取り消す CommandHandler。 */
  @Autowired private CancelOrderCommandHandler cancelOrder;

  /** 商品の行を登録し、イベント出版レジストリを読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 共通カラムの trace ID を作る observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

  @Test
  @DisplayName("下書きの注文を確定すると OrderConfirmed を発行し、イベント出版レジストリに記録する")
  void confirmPublishesOrderConfirmed(final Scenario scenario) {
    final String orderId = draftedOrderId();

    Observation.createNotStarted("order-test", observationRegistry)
        .observe(
            () ->
                scenario
                    .stimulate(
                        () ->
                            confirmOrder.handle(
                                new ConfirmOrderCommand(orderId, new ExpectedLockNo(1))))
                    .andWaitForEventOfType(OrderConfirmed.class)
                    .matching(event -> event.orderId().equals(orderId))
                    .toArriveAndVerify(
                        event ->
                            assertThat(event.confirmedAt())
                                .as("orderId=%s の confirmedAt", orderId)
                                .isNotNull()));

    assertThat(recordedPublications(orderId))
        .as("orderId=%s の OrderConfirmed の出版の記録", orderId)
        .isEqualTo(1);
  }

  @Test
  @DisplayName("古いロック番号の確定は 409 の例外になり、OrderConfirmed を発行も記録もしない")
  void staleLockNoPublishesNothing(final AssertablePublishedEvents events) {
    final String orderId = draftedOrderId();

    assertThatThrownBy(
            () ->
                observed(
                    () ->
                        confirmOrder.handle(
                            new ConfirmOrderCommand(orderId, new ExpectedLockNo(2)))))
        .isInstanceOf(ConflictException.class);

    assertThat(events.ofType(OrderConfirmed.class)).as("orderId=%s の発行", orderId).isEmpty();
    assertThat(recordedPublications(orderId)).as("orderId=%s の出版の記録", orderId).isZero();
  }

  @Test
  @DisplayName("取り消した注文の確定は 422 の例外になり、OrderConfirmed を発行も記録もしない")
  void cancelledOrderPublishesNothing(final AssertablePublishedEvents events) {
    final String orderId = draftedOrderId();
    observed(() -> cancelOrder.handle(new CancelOrderCommand(orderId, new ExpectedLockNo(1))));

    assertThatThrownBy(
            () ->
                observed(
                    () ->
                        confirmOrder.handle(
                            new ConfirmOrderCommand(orderId, new ExpectedLockNo(2)))))
        .isInstanceOf(BusinessRuleViolationException.class);

    assertThat(events.ofType(OrderConfirmed.class)).as("orderId=%s の発行", orderId).isEmpty();
    assertThat(recordedPublications(orderId)).as("orderId=%s の出版の記録", orderId).isZero();
  }

  /** 販売中の商品で下書きの注文を作り、注文 ID を返す。 */
  private String draftedOrderId() {
    final UUID productId = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    return observed(
        () ->
            draftOrder
                .handle(
                    new DraftOrderCommand(
                        UniqueCodes.next("C"),
                        List.of(new DraftOrderCommand.Line(productId.toString(), 2))))
                .orderId());
  }

  /** 共通カラムの trace ID を取れるよう、observation の中で処理を呼ぶ。 */
  private <T> T observed(final Supplier<T> action) {
    return Observation.createNotStarted("order-test", observationRegistry).observe(action);
  }

  /** イベント出版レジストリの、未完了と完了済みの OrderConfirmed の出版のうち、注文 ID を含む件数を返す。 */
  private int recordedPublications(final String orderId) {
    final String eventType = OrderConfirmed.class.getName();
    return dsl.fetchCount(
        dsl.select(EVENT_PUBLICATION.ID)
            .from(EVENT_PUBLICATION)
            .where(
                EVENT_PUBLICATION
                    .EVENT_TYPE
                    .eq(eventType)
                    .and(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(orderId)))
            .unionAll(
                dsl.select(EVENT_PUBLICATION_ARCHIVE.ID)
                    .from(EVENT_PUBLICATION_ARCHIVE)
                    .where(
                        EVENT_PUBLICATION_ARCHIVE
                            .EVENT_TYPE
                            .eq(eventType)
                            .and(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT.contains(orderId)))));
  }

  /** OrderConfirmed を受けるだけのテストのリスナー。イベント出版レジストリに出版を記録させる。 */
  // 非同期とトランザクションのプロキシを作れるよう、final にしない。
  @SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
  /* package */ static class OrderConfirmedProbe {

    /** イベントを受ける。何もしない。 */
    @ApplicationModuleListener
    public void on(final OrderConfirmed event) {
      // 出版を記録させるためだけに受ける。
    }
  }

  /** テストのリスナーの Bean を登録する。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class ProbeConfiguration {

    @Bean
    /* package */ OrderConfirmedProbe orderConfirmedProbe() {
      return new OrderConfirmedProbe();
    }
  }
}
