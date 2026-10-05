package archfixture.conforming.order.infrastructure.persistence;

import archfixture.conforming.order.domain.model.CustomerId;
import archfixture.conforming.order.domain.model.Order;
import archfixture.conforming.order.domain.model.OrderId;
import archfixture.conforming.order.domain.model.OrderRepository;
import archfixture.conforming.order.domain.model.OrderStatus;
import archfixture.conforming.shared.infrastructure.persistence.CommonColumns;
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
 * <p>共通カラムの値は shared の共通処理から受け取る。
 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /** 保存した注文。 */
  private final Map<OrderId, Order> orders = new ConcurrentHashMap<>();

  /** 保存した注文の共通カラムの値。 */
  private final Map<OrderId, Map<String, Object>> commonColumnValues = new ConcurrentHashMap<>();

  /** 共通カラムの値を作る shared の共通処理。 */
  private final CommonColumns commonColumns;

  /** shared の共通処理を受け取る。 */
  /* package */ JooqOrderRepository(final CommonColumns commonColumns) {
    this.commonColumns = commonColumns;
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
}
