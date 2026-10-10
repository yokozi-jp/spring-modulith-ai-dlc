package com.example.demo.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.demo.ordering.OrderDetails;
import com.example.demo.ordering.OrderQueries;
import com.example.demo.ordering.OrderSearchCriteria;
import com.example.demo.ordering.OrderSummary;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;

/** 注文の参照を、OrderQueries を通して検証する。 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class OrderQueryServiceTest {

  /** テスト対象の参照のインタフェース。 */
  @Autowired private OrderQueries orderQueries;

  /** 参照する注文を作る CommandHandler。 */
  @Autowired private DraftOrderCommandHandler draftOrder;

  /** 確定の判定の準備に注文を確定する CommandHandler。 */
  @Autowired private ConfirmOrderCommandHandler confirmOrder;

  /** 状態の絞り込みの準備に注文を取り消す CommandHandler。 */
  @Autowired private CancelOrderCommandHandler cancelOrder;

  /** 商品の行を登録する jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 共通カラムの trace ID を作る observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

  @Test
  @DisplayName("保存した注文の詳細を、明細の金額と合計と状態の名前で返す")
  void findsDetailsOfSavedOrder() {
    final UUID pen = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    final UUID eraser = TestProducts.onSale(dsl, UniqueCodes.next("P"), "80.00");
    final String customerOrderCode = UniqueCodes.next("C");
    final String orderId = draft(customerOrderCode, pen, 2, eraser, 1);

    final OrderDetails details =
        orderQueries
            .findDetails(orderId)
            .orElseThrow(() -> new AssertionError("order が見つからない: orderId=" + orderId));

    assertThat(details.status()).as("orderId=%s の状態", orderId).isEqualTo("DRAFT");
    assertThat(details.confirmed()).as("orderId=%s の確定", orderId).isFalse();
    assertThat(details.customerOrderCode()).isEqualTo(customerOrderCode);
    assertThat(details.lockNo()).isEqualTo(1L);
    assertThat(details.totalAmount()).isEqualByComparingTo(new BigDecimal("320"));
    assertThat(details.lines())
        .as("orderId=%s の明細", orderId)
        .extracting(
            OrderDetails.Line::lineNumber,
            OrderDetails.Line::productId,
            OrderDetails.Line::quantity)
        .containsExactly(tuple(1, pen.toString(), 2), tuple(2, eraser.toString(), 1));
    assertThat(details.lines().get(0).amount()).isEqualByComparingTo(new BigDecimal("240"));
  }

  @Test
  @DisplayName("詳細の confirmed は、確定した注文だけ true を返す")
  void confirmedIsTrueOnlyForConfirmedOrder() {
    final UUID pen = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    final String confirmed = draft(UniqueCodes.next("C"), pen, 1, pen, 1);
    final String cancelled = draft(UniqueCodes.next("C"), pen, 1, pen, 1);
    observed(() -> confirmOrder.handle(new ConfirmOrderCommand(confirmed, new ExpectedLockNo(1))));
    observed(() -> cancelOrder.handle(new CancelOrderCommand(cancelled, new ExpectedLockNo(1))));

    assertThat(orderQueries.findDetails(confirmed))
        .as("orderId=%s の確定", confirmed)
        .hasValueSatisfying(details -> assertThat(details.confirmed()).isTrue());
    assertThat(orderQueries.findDetails(cancelled))
        .as("orderId=%s の確定", cancelled)
        .hasValueSatisfying(details -> assertThat(details.confirmed()).isFalse());
  }

  @Test
  @DisplayName("存在しない注文の詳細は空を返す")
  void returnsEmptyForMissingOrder() {
    final String orderId = UUID.randomUUID().toString();

    assertThat(orderQueries.findDetails(orderId)).as("orderId=%s の詳細", orderId).isEmpty();
  }

  @Test
  @DisplayName("一覧は作成の新しい順に返し、状態を指定するとその状態の注文だけを返す")
  void searchesByStatus() {
    final UUID pen = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    final String first = draft(UniqueCodes.next("C"), pen, 1, pen, 1);
    final String second = draft(UniqueCodes.next("C"), pen, 1, pen, 1);
    observed(() -> cancelOrder.handle(new CancelOrderCommand(first, new ExpectedLockNo(1))));

    assertThat(orderQueries.search(new OrderSearchCriteria(null)))
        .extracting(OrderSummary::orderId)
        .as("すべての状態の一覧")
        .containsExactly(second, first);
    assertThat(orderQueries.search(new OrderSearchCriteria("CANCELLED")))
        .extracting(OrderSummary::orderId, OrderSummary::lockNo)
        .as("CANCELLED の一覧")
        .containsExactly(tuple(first, 2L));
    assertThat(orderQueries.search(new OrderSearchCriteria("CONFIRMED")))
        .as("CONFIRMED の一覧")
        .isEmpty();
  }

  /** 2 行の明細で下書きの注文を作り、注文 ID を返す。 */
  private String draft(
      final String customerOrderCode,
      final UUID firstProduct,
      final int firstQuantity,
      final UUID secondProduct,
      final int secondQuantity) {
    return observed(
        () ->
            draftOrder
                .handle(
                    new DraftOrderCommand(
                        customerOrderCode,
                        List.of(
                            new DraftOrderCommand.Line(firstProduct.toString(), firstQuantity),
                            new DraftOrderCommand.Line(secondProduct.toString(), secondQuantity))))
                .orderId());
  }

  /** 共通カラムの trace ID を取れるよう、observation の中で処理を呼ぶ。 */
  private <T> T observed(final Supplier<T> action) {
    return Observation.createNotStarted("order-test", observationRegistry).observe(action);
  }
}
