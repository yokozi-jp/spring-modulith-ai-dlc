package com.example.demo.ordering.infrastructure.persistence;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

import com.example.demo.jooq.ordering.Tables;
import com.example.demo.jooq.ordering.tables.records.TOrderLineRecord;
import com.example.demo.jooq.ordering.tables.records.TOrderRecord;
import com.example.demo.ordering.domain.model.Money;
import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderId;
import com.example.demo.ordering.domain.model.OrderLine;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.ordering.domain.model.OrderStatus;
import com.example.demo.ordering.domain.model.ProductId;
import com.example.demo.ordering.domain.model.Quantity;
import com.example.demo.shared.persistence.AggregateTable;
import com.example.demo.shared.persistence.ChildTable;
import com.example.demo.shared.persistence.ColumnValues;
import com.example.demo.shared.persistence.TableWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record6;
import org.jooq.Records;
import org.jooq.SelectJoinStep;
import org.springframework.stereotype.Repository;

/** 注文の集約を jOOQ で保存し、取り出す。 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /**
   * 注文の明細。親の中の行は、明細番号で同定する。
   *
   * <p>外す前の行を h_order_line に残す（ADR-131）。
   */
  private static final ChildTable<TOrderLineRecord> LINES =
      ChildTable.of(T_ORDER_LINE, T_ORDER_LINE.ORDER_ID, Tables.H_ORDER_LINE, T_ORDER_LINE.LINE_NO);

  /** 注文の集約。ルートは公開 ID で一つに決める。 */
  private static final AggregateTable<TOrderRecord, UUID, Order> ORDER =
      AggregateTable.of(
          T_ORDER, T_ORDER.PUBLIC_ID, order -> order.id().value(), T_ORDER.ORDER_ID, LINES);

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 注文の集約を INSERT と UPDATE で書く shared の入口。 */
  private final TableWriter tableWriter;

  /** jOOQ のコンテキストと shared の入口を受け取る。 */
  /* package */ JooqOrderRepository(final DSLContext dsl, final TableWriter tableWriter) {
    this.dsl = dsl;
    this.tableWriter = tableWriter;
  }

  @Override
  public Optional<Order> findById(final OrderId id) {
    return selectOrders()
        .where(T_ORDER.PUBLIC_ID.eq(id.value()))
        .fetchOptional(Records.mapping(Order::restore));
  }

  @Override
  public List<Order> findAll() {
    return selectOrders()
        .orderBy(T_ORDER.ORDERED_AT.desc(), T_ORDER.ORDER_ID.desc())
        .fetch(Records.mapping(Order::restore));
  }

  @Override
  public List<Order> findByStatus(final OrderStatus status) {
    return selectOrders()
        .where(T_ORDER.ORDER_STATUS_TYP.eq(status.name()))
        .orderBy(T_ORDER.ORDERED_AT.desc(), T_ORDER.ORDER_ID.desc())
        .fetch(Records.mapping(Order::restore));
  }

  @Override
  public void add(final Order order) {
    // ユニークインデックスは public_id（UUID v4 で衝突しない）と customer_order_code だけなので、409 は客先注文番号の重複である。
    tableWriter.insert(
        ORDER,
        order,
        set -> rootColumns(order, set),
        LINES.rows(order.lines(), JooqOrderRepository::lineColumns));
  }

  @Override
  public void update(final Order order) {
    tableWriter.update(
        ORDER,
        order,
        set -> rootColumns(order, set),
        LINES.rows(order.lines(), JooqOrderRepository::lineColumns));
  }

  /** 注文の列の値。公開 ID は {@link TableWriter} が書く。 */
  private static void rootColumns(final Order order, final ColumnValues<TOrderRecord> set) {
    set.set(T_ORDER.CUSTOMER_ORDER_CODE, order.customerOrderCode())
        .set(T_ORDER.ORDER_STATUS_TYP, order.status().name())
        .set(T_ORDER.ORDERED_AT, order.orderedAt());
  }

  /** 明細の列の値。注文の内部の主キーは {@link TableWriter} が書く。 */
  private static void lineColumns(final OrderLine line, final ColumnValues<TOrderLineRecord> set) {
    set.set(T_ORDER_LINE.LINE_NO, line.lineNumber())
        .set(T_ORDER_LINE.PRODUCT_PUBLIC_ID, line.productId().value())
        .set(T_ORDER_LINE.ORDERED_COUNT, line.quantity().value())
        .set(T_ORDER_LINE.ORDERED_UNIT_PRICE_JPY, line.unitPrice().amount());
  }

  /** 注文の列と明細を、Order.restore の引数の型と順に選ぶ。 */
  private SelectJoinStep<Record6<OrderId, String, OrderStatus, List<OrderLine>, Instant, Long>>
      selectOrders() {
    return dsl.select(
            T_ORDER.PUBLIC_ID.convertFrom(OrderId::new),
            T_ORDER.CUSTOMER_ORDER_CODE,
            T_ORDER.ORDER_STATUS_TYP.convertFrom(OrderStatus::valueOf),
            multiset(
                    select(
                            T_ORDER_LINE.LINE_NO,
                            T_ORDER_LINE.PRODUCT_PUBLIC_ID.convertFrom(ProductId::new),
                            T_ORDER_LINE.ORDERED_COUNT.convertFrom(Quantity::new),
                            T_ORDER_LINE.ORDERED_UNIT_PRICE_JPY.convertFrom(Money::new))
                        .from(T_ORDER_LINE)
                        .where(T_ORDER_LINE.ORDER_ID.eq(T_ORDER.ORDER_ID))
                        .orderBy(T_ORDER_LINE.LINE_NO))
                .convertFrom(lines -> lines.map(Records.mapping(OrderLine::new))),
            T_ORDER.ORDERED_AT,
            T_ORDER.LOCK_NO)
        .from(T_ORDER);
  }
}
