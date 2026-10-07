package archfixture.conforming.ordering.infrastructure.persistence;

import archfixture.conforming.ordering.domain.model.CustomerId;
import archfixture.conforming.ordering.domain.model.Order;
import archfixture.conforming.ordering.domain.model.OrderId;
import archfixture.conforming.ordering.domain.model.OrderRepository;
import archfixture.conforming.ordering.domain.model.OrderStatus;
import archfixture.conforming.shared.infrastructure.persistence.CommonColumns;
import archfixture.conforming.shared.infrastructure.persistence.TableWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 注文の Repository の実装。フィクスチャなので jOOQ の代わりに Map へ保存する。
 *
 * <p>フィクスチャなので ID の採番は仮の実装である。
 *
 * <p>共通カラムの値は shared の共通処理から受け取り、UPDATE と DELETE は shared の TableWriter で書く。
 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /** 保存した注文。 */
  private final Map<OrderId, Order> orders = new ConcurrentHashMap<>();

  /** 保存した注文の共通カラムの値。 */
  private final Map<OrderId, Map<String, Object>> commonColumnValues = new ConcurrentHashMap<>();

  /** 共通カラムの値を作る shared の共通処理。 */
  private final CommonColumns commonColumns;

  /** 業務テーブルの UPDATE と DELETE の入口。 */
  private final TableWriter tableWriter;

  /** shared の共通処理を受け取る。 */
  /* package */ JooqOrderRepository(
      final CommonColumns commonColumns, final TableWriter tableWriter) {
    this.commonColumns = commonColumns;
    this.tableWriter = tableWriter;
  }

  @Override
  public OrderId nextId() {
    return new OrderId(UUID.randomUUID());
  }

  @Override
  public Optional<Order> findById(final OrderId id) {
    return Optional.ofNullable(orders.get(id));
  }

  @Override
  public List<Order> findByCustomer(final CustomerId customerId) {
    return orders.values().stream().filter(order -> order.customerId().equals(customerId)).toList();
  }

  @Override
  public long countUnshippedByCustomer(final CustomerId customerId) {
    return findByCustomer(customerId).stream()
        .filter(order -> order.status() == OrderStatus.PLACED)
        .count();
  }

  @Override
  public void add(final Order order) {
    orders.put(order.id(), order);
    commonColumnValues.put(order.id(), commonColumns.forInsert());
  }

  @Override
  public void update(final Order order) {
    commonColumnValues.put(
        order.id(), Map.of("result", tableWriter.updateCheckingVersion("orders", order.lockNo())));
    orders.put(order.id(), order);
  }

  /** 期限を過ぎた取消済みの注文を、集約を読み込まずに一括で削除し、件数を返す。 */
  /* package */ int purgeCancelledBefore(final Instant threshold) {
    return tableWriter.deleteWhere("orders:" + threshold);
  }
}
