package com.example.demo.ordering.infrastructure.persistence;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.ordering.Tables.T_ORDER_LINE;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

import com.example.demo.jooq.ordering.tables.records.TOrderLineRecord;
import com.example.demo.ordering.domain.model.Money;
import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderId;
import com.example.demo.ordering.domain.model.OrderLine;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.ordering.domain.model.OrderStatus;
import com.example.demo.ordering.domain.model.ProductId;
import com.example.demo.ordering.domain.model.Quantity;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.LockedRoot;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.InsertSetMoreStep;
import org.jooq.Record6;
import org.jooq.Records;
import org.jooq.SelectJoinStep;
import org.springframework.stereotype.Repository;

/** 注文の集約を jOOQ で保存し、取り出す。 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る shared の共通処理。 */
  private final CommonColumns commonColumns;

  /** 業務テーブルの UPDATE と DELETE を書く shared の入口。 */
  private final TableWriter tableWriter;

  /** jOOQ のコンテキストと shared の共通処理を受け取る。 */
  /* package */ JooqOrderRepository(
      final DSLContext dsl, final CommonColumns commonColumns, final TableWriter tableWriter) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
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
        dsl.insertInto(T_ORDER)
            .set(T_ORDER.PUBLIC_ID, order.id().value())
            .set(T_ORDER.CUSTOMER_ORDER_CODE, order.customerOrderCode())
            .set(T_ORDER.ORDER_STATUS_TYP, order.status().name())
            .set(T_ORDER.ORDERED_AT, order.orderedAt())
            .set(commonColumns.forInsert(T_ORDER)));
    // TableWriter.insert は RETURNING の値を返さないため、内部の主キーを読み直す。
    final Long orderId =
        dsl.select(T_ORDER.ORDER_ID)
            .from(T_ORDER)
            .where(T_ORDER.PUBLIC_ID.eq(order.id().value()))
            .fetchSingle()
            .value1();
    dsl.batch(order.lines().stream().map(line -> insertLine(orderId, line)).toList()).execute();
  }

  @Override
  public void update(final Order order) {
    final Condition byId = T_ORDER.PUBLIC_ID.eq(order.id().value());
    final LockedRoot root =
        tableWriter.updateCheckingVersion(
            T_ORDER,
            byId,
            order.lockNo(),
            set ->
                set.set(T_ORDER.CUSTOMER_ORDER_CODE, order.customerOrderCode())
                    .set(T_ORDER.ORDER_STATUS_TYP, order.status().name())
                    .set(T_ORDER.ORDERED_AT, order.orderedAt()));
    // ルートの行をロックした後に内部の主キーを読む。件数が 0 なら先に例外になるため、必ず 1 行ある。
    final Long orderId =
        dsl.select(T_ORDER.ORDER_ID).from(T_ORDER).where(byId).fetchSingle().value1();
    final Set<Integer> saved =
        dsl.select(T_ORDER_LINE.LINE_NO)
            .from(T_ORDER_LINE)
            .where(T_ORDER_LINE.ORDER_ID.eq(orderId))
            .fetchSet(T_ORDER_LINE.LINE_NO);
    final List<Integer> numbers = order.lines().stream().map(OrderLine::lineNumber).toList();
    root.deleteChildren(
        T_ORDER_LINE, T_ORDER_LINE.ORDER_ID.eq(orderId).and(T_ORDER_LINE.LINE_NO.notIn(numbers)));
    // 明細の主キーは番号の昇順に INSERT した IDENTITY なので、番号の昇順は主キーの昇順である。
    // ponytail: 確定と取消でも明細の数（最大 100）だけ UPDATE を 1 行ずつ実行する。書き込みが問題になったら LockedRoot を batch にする。
    for (final OrderLine line : order.lines()) {
      if (saved.contains(line.lineNumber())) {
        root.updateChild(
            T_ORDER_LINE,
            T_ORDER_LINE.ORDER_ID.eq(orderId).and(T_ORDER_LINE.LINE_NO.eq(line.lineNumber())),
            set ->
                set.set(T_ORDER_LINE.PRODUCT_PUBLIC_ID, line.productId().value())
                    .set(T_ORDER_LINE.ORDERED_COUNT, line.quantity().value())
                    .set(T_ORDER_LINE.ORDERED_UNIT_PRICE_JPY, line.unitPrice().amount()));
      } else {
        insertLine(orderId, line).execute();
      }
    }
  }

  /** 明細の行の INSERT を組み立てる。{@link #add} と {@link #update} の追加で共有する。 */
  private InsertSetMoreStep<TOrderLineRecord> insertLine(final Long orderId, final OrderLine line) {
    return dsl.insertInto(T_ORDER_LINE)
        .set(T_ORDER_LINE.ORDER_ID, orderId)
        .set(T_ORDER_LINE.LINE_NO, line.lineNumber())
        .set(T_ORDER_LINE.PRODUCT_PUBLIC_ID, line.productId().value())
        .set(T_ORDER_LINE.ORDERED_COUNT, line.quantity().value())
        .set(T_ORDER_LINE.ORDERED_UNIT_PRICE_JPY, line.unitPrice().amount())
        .set(commonColumns.forInsert(T_ORDER_LINE));
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
