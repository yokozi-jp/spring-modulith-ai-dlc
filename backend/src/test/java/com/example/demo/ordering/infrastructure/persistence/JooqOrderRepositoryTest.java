package com.example.demo.ordering.infrastructure.persistence;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.example.demo.ordering.domain.model.Money;
import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderLine;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.ordering.domain.model.OrderStatus;
import com.example.demo.ordering.domain.model.OrderedItem;
import com.example.demo.ordering.domain.model.ProductId;
import com.example.demo.ordering.domain.model.ProductOffer;
import com.example.demo.ordering.domain.model.Quantity;
import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.UniqueCodes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.jooq.DSLContext;
import org.jooq.Record4;
import org.jooq.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 注文の保存と読み戻しと、明細の差分の保存を検証する。競合と行なしは TableWriterTest が確かめる。 */
// 生成したテーブルと AssertJ の tuple を static import で読みやすくする。
@SuppressWarnings("PMD.TooManyStaticImports")
@DatabaseTest
class JooqOrderRepositoryTest {

  /** 客先注文番号。 */
  private static final String CUSTOMER_ORDER_CODE = UniqueCodes.next("C");

  /** 作成した時刻。 */
  private static final Instant DRAFTED_AT = Instant.parse("2026-10-05T00:00:00Z");

  /** 保存の 1 回目の時刻。 */
  private static final Instant ADDED_AT = Instant.parse("2026-10-05T01:00:00.123456Z");

  /** 保存の 2 回目の時刻。 */
  private static final Instant UPDATED_AT = Instant.parse("2026-10-05T02:00:00.654321Z");

  /** 保存の 3 回目の時刻。 */
  private static final Instant UPDATED_AGAIN_AT = Instant.parse("2026-10-05T03:00:00Z");

  /** 単価 120 円の商品。 */
  private static final ProductOffer PEN =
      new ProductOffer(new ProductId(UUID.randomUUID()), new Money(new BigDecimal("120.00")), true);

  /** 単価 80 円の商品。 */
  private static final ProductOffer ERASER =
      new ProductOffer(new ProductId(UUID.randomUUID()), new Money(new BigDecimal("80.00")), true);

  /** 単価 300 円の商品。 */
  private static final ProductOffer NOTE =
      new ProductOffer(new ProductId(UUID.randomUUID()), new Money(new BigDecimal("300.00")), true);

  /** 明細の商品の単価。 */
  private static final Map<ProductId, ProductOffer> OFFERS =
      Map.of(PEN.productId(), PEN, ERASER.productId(), ERASER, NOTE.productId(), NOTE);

  /** テスト対象が使う jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("保存した注文を、明細、単価、状態、ロック番号ごと読み戻せる")
  void savesAndFindsOrder() {
    final Order order = draft(CUSTOMER_ORDER_CODE, item(PEN, 2), item(ERASER, 1));
    TestCommonColumns.runAs(() -> repository(ADDED_AT).add(order));

    final Order found = find(order);

    assertThat(found.customerOrderCode()).isEqualTo(CUSTOMER_ORDER_CODE);
    assertThat(found.status()).isEqualTo(OrderStatus.DRAFT);
    assertThat(found.orderedAt()).isEqualTo(DRAFTED_AT);
    assertThat(found.lockNo()).isEqualTo(1L);
    assertThat(found.total().amount()).isEqualByComparingTo(new BigDecimal("320"));
    assertThat(found.lines())
        .as("orderId=%s の明細", order.id().value())
        .extracting(
            OrderLine::lineNumber,
            OrderLine::productId,
            line -> line.quantity().value(),
            line -> line.unitPrice().amount())
        .containsExactly(
            tuple(1, PEN.productId(), 2, new BigDecimal("120.00")),
            tuple(2, ERASER.productId(), 1, new BigDecimal("80.00")));
  }

  @Test
  @DisplayName("客先注文番号が重複する保存は、種類が UNIQUE の ConflictException になる")
  void duplicateCustomerOrderCodeBecomesConflict() {
    final OrderRepository repository = repository(ADDED_AT);
    final String customerOrderCode = UniqueCodes.next("C");
    TestCommonColumns.runAs(() -> repository.add(draft(customerOrderCode, item(PEN, 1))));
    final Order duplicate = draft(customerOrderCode, item(ERASER, 1));

    assertThatThrownBy(() -> TestCommonColumns.runAs(() -> repository.add(duplicate)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            conflict ->
                assertThat(conflict.kind())
                    .as("customerOrderCode=%s の 2 件目の保存の衝突の種類", customerOrderCode)
                    .isEqualTo(ConflictException.Kind.UNIQUE));
  }

  @Test
  @DisplayName("明細を減らすと消えた明細を削除し、残る明細は作成の記録を保って版を進め、増やすと新しい明細を版 1 で足す")
  void updatesLinesByDifference() {
    final Order order = draft(CUSTOMER_ORDER_CODE, item(PEN, 1), item(ERASER, 1), item(NOTE, 1));
    TestCommonColumns.runAs(() -> repository(ADDED_AT).add(order));

    final Order shrunk = find(order);
    shrunk.changeLines(List.of(item(NOTE, 2), item(PEN, 3)), OFFERS);
    TestCommonColumns.runAs(() -> repository(UPDATED_AT).update(shrunk));

    assertThat(lineRows(order))
        .as("orderId=%s の 2 件に減らした明細（番号、版、作成、更新）", order.id().value())
        .containsExactly(tuple(1, 2L, ADDED_AT, UPDATED_AT), tuple(2, 2L, ADDED_AT, UPDATED_AT));
    assertThat(find(order).lines())
        .extracting(OrderLine::productId, line -> line.quantity().value())
        .containsExactly(tuple(NOTE.productId(), 2), tuple(PEN.productId(), 3));

    final Order grown = find(order);
    grown.changeLines(List.of(item(NOTE, 2), item(PEN, 3), item(ERASER, 4)), OFFERS);
    TestCommonColumns.runAs(() -> repository(UPDATED_AGAIN_AT).update(grown));

    assertThat(lineRows(order))
        .as("orderId=%s の 3 件に増やした明細", order.id().value())
        .containsExactly(
            tuple(1, 3L, ADDED_AT, UPDATED_AGAIN_AT),
            tuple(2, 3L, ADDED_AT, UPDATED_AGAIN_AT),
            tuple(3, 1L, UPDATED_AGAIN_AT, UPDATED_AGAIN_AT));
    assertThat(find(order).lockNo()).as("orderId=%s のルートの版", order.id().value()).isEqualTo(3L);
  }

  @Test
  @DisplayName("確定の保存でも明細の作成の記録は変わらない")
  void confirmKeepsLineCreation() {
    final Order order = draft(CUSTOMER_ORDER_CODE, item(PEN, 1));
    TestCommonColumns.runAs(() -> repository(ADDED_AT).add(order));

    final Order confirmed = find(order);
    confirmed.confirm();
    TestCommonColumns.runAs(() -> repository(UPDATED_AT).update(confirmed));

    assertThat(find(order).status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(lineRows(order))
        .as("orderId=%s の確定後の明細", order.id().value())
        .containsExactly(tuple(1, 2L, ADDED_AT, UPDATED_AT));
  }

  private OrderRepository repository(final Instant at) {
    final CommonColumns commonColumns = TestCommonColumns.at(at);
    return new JooqOrderRepository(dsl, commonColumns, new TableWriter(dsl, commonColumns));
  }

  private Order find(final Order order) {
    return repository(ADDED_AT)
        .findById(order.id())
        .orElseThrow(() -> new AssertionError("order が見つからない: orderId=" + order.id().value()));
  }

  /** 明細の行の番号、版、作成と更新の時刻を、番号の順に読む。 */
  private List<Tuple> lineRows(final Order order) {
    final Result<Record4<Integer, Long, Instant, Instant>> rows =
        dsl.select(
                T_ORDER_LINE.LINE_NO,
                T_ORDER_LINE.LOCK_NO,
                T_ORDER_LINE.CREATED_AT,
                T_ORDER_LINE.UPDATED_AT)
            .from(T_ORDER_LINE)
            .join(T_ORDER)
            .on(T_ORDER.ORDER_ID.eq(T_ORDER_LINE.ORDER_ID))
            .where(T_ORDER.PUBLIC_ID.eq(order.id().value()))
            .orderBy(T_ORDER_LINE.LINE_NO)
            .fetch();
    return rows.map(row -> tuple(row.value1(), row.value2(), row.value3(), row.value4()));
  }

  private static Order draft(final String customerOrderCode, final OrderedItem... items) {
    return Order.draft(customerOrderCode, List.of(items), OFFERS, DRAFTED_AT);
  }

  private static OrderedItem item(final ProductOffer offer, final int quantity) {
    return new OrderedItem(offer.productId(), new Quantity(quantity));
  }
}
