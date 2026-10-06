package com.example.demo.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 注文の作成、明細の置き換え、状態遷移、ロック番号の確認を検証する。 */
// 集約の操作ごとにテストを分けるため、メソッドの数の上限を外す。
@SuppressWarnings("PMD.TooManyMethods")
class OrderTest {

  /** 作成の時刻を取る時計。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

  /** 販売中で単価 120 円の商品。 */
  private static final ProductOffer PEN =
      new ProductOffer(new ProductId(UUID.randomUUID()), new Money(new BigDecimal("120")), true);

  /** 販売中で単価 80 円の商品。 */
  private static final ProductOffer ERASER =
      new ProductOffer(new ProductId(UUID.randomUUID()), new Money(new BigDecimal("80")), true);

  /** 販売終了の商品。 */
  private static final ProductOffer DISCONTINUED =
      new ProductOffer(new ProductId(UUID.randomUUID()), new Money(new BigDecimal("300")), false);

  /** 明細の商品の単価と販売の状態。 */
  private static final Map<ProductId, ProductOffer> OFFERS =
      Map.of(
          PEN.productId(), PEN, ERASER.productId(), ERASER, DISCONTINUED.productId(), DISCONTINUED);

  @Test
  @DisplayName("下書きの注文は UUID v4 の ID、DRAFT、ロック番号 1、1 からの明細の番号と合計を持つ")
  void draftCreatesDraftOrder() {
    final Order order = draft(item(PEN, 2), item(ERASER, 1));

    assertThat(order.id().value().version()).as("注文 ID の UUID の版").isEqualTo(4);
    assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
    assertThat(order.lockNo()).isEqualTo(1L);
    assertThat(order.orderedAt()).isEqualTo(Instant.now(CLOCK));
    assertThat(order.total().amount()).isEqualByComparingTo(new BigDecimal("320"));
    assertThat(order.lines())
        .extracting(OrderLine::lineNumber, OrderLine::productId)
        .containsExactly(tuple(1, PEN.productId()), tuple(2, ERASER.productId()));
  }

  @Test
  @DisplayName("存在しない商品の明細は、注文 ID と商品 ID を含む BusinessRuleViolationException になる")
  void draftRejectsMissingProduct() {
    final ProductId missing = new ProductId(UUID.randomUUID());

    assertThatThrownBy(
            () ->
                Order.draft(
                    "C-0001",
                    List.of(new OrderedItem(missing, new Quantity(1))),
                    OFFERS,
                    Instant.now(CLOCK)))
        .isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageStartingWith("product not found: orderId=")
        .hasMessageContaining("productId=" + missing.value());
  }

  @Test
  @DisplayName("販売終了の商品の明細は BusinessRuleViolationException になる")
  void draftRejectsDiscontinuedProduct() {
    assertThatThrownBy(() -> draft(item(DISCONTINUED, 1)))
        .isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageStartingWith("product is discontinued: orderId=")
        .hasMessageContaining("productId=" + DISCONTINUED.productId().value());
  }

  @Test
  @DisplayName("明細のない注文は BusinessRuleViolationException になる")
  void draftRejectsEmptyLines() {
    assertThatThrownBy(() -> draft())
        .isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageStartingWith("order lines must not be empty: orderId=");
  }

  @Test
  @DisplayName("明細の置き換えは、番号を 1 から振り直して合計を変える")
  void changeLinesRenumbersLines() {
    final Order order = draft(item(PEN, 1), item(ERASER, 1));

    order.changeLines(List.of(item(ERASER, 3)), OFFERS);

    assertThat(order.lines())
        .extracting(OrderLine::lineNumber, OrderLine::productId, line -> line.quantity().value())
        .containsExactly(tuple(1, ERASER.productId(), 3));
    assertThat(order.total().amount()).isEqualByComparingTo(new BigDecimal("240"));
  }

  @Test
  @DisplayName("下書きの注文は確定と取消ができる")
  void draftCanBeConfirmedOrCancelled() {
    final Order confirmed = draft(item(PEN, 1));
    confirmed.confirm();
    final Order cancelled = draft(item(PEN, 1));
    cancelled.cancel();

    assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
  }

  /** 下書きでない注文への操作。 */
  private static Stream<Arguments> operationsOnNonDraft() {
    final Consumer<Order> confirm = Order::confirm;
    final Consumer<Order> cancel = Order::cancel;
    final Consumer<Order> changeLines = order -> order.changeLines(List.of(item(PEN, 1)), OFFERS);
    return Stream.of(
        Arguments.of("確定済みの確定", confirm, confirm),
        Arguments.of("確定済みの取消", confirm, cancel),
        Arguments.of("確定済みの明細の置き換え", confirm, changeLines),
        Arguments.of("取消済みの確定", cancel, confirm));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("operationsOnNonDraft")
  @DisplayName("下書きでない注文の操作は、状態を含む BusinessRuleViolationException になる")
  void nonDraftOrderRejectsOperation(
      final String name, final Consumer<Order> prepare, final Consumer<Order> operation) {
    final Order order = draft(item(PEN, 1));
    prepare.accept(order);
    final OrderStatus before = order.status();

    assertThatThrownBy(() -> operation.accept(order))
        .as(name)
        .isInstanceOf(BusinessRuleViolationException.class)
        .hasMessage("order is not draft: orderId=" + order.id().value() + ", status=" + before);
    assertThat(order.status()).as(name).isEqualTo(before);
  }

  @Test
  @DisplayName("ロック番号が違えば、注文 ID と両方の版を含む ConflictException になる")
  void ensureLockNoRejectsStaleLockNo() {
    final Order order = draft(item(PEN, 1));

    order.ensureLockNo(new ExpectedLockNo(1));
    assertThatThrownBy(() -> order.ensureLockNo(new ExpectedLockNo(2)))
        .isInstanceOf(ConflictException.class)
        .hasMessage(
            "lock number mismatch: orderId=" + order.id().value() + ", expectedLockNo=2, lockNo=1");
  }

  @Test
  @DisplayName("1 未満の数量と負の金額は BusinessRuleViolationException になる")
  void valueObjectsRejectInvalidValues() {
    assertThatThrownBy(() -> new Quantity(0)).isInstanceOf(BusinessRuleViolationException.class);
    assertThatThrownBy(() -> new Money(new BigDecimal("-1")))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  private static Order draft(final OrderedItem... items) {
    return Order.draft("C-0001", List.of(items), OFFERS, Instant.now(CLOCK));
  }

  private static OrderedItem item(final ProductOffer offer, final int quantity) {
    return new OrderedItem(offer.productId(), new Quantity(quantity));
  }
}
