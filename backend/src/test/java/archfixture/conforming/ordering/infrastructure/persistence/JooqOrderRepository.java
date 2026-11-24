package archfixture.conforming.ordering.infrastructure.persistence;

import archfixture.conforming.ordering.domain.model.CustomerId;
import archfixture.conforming.ordering.domain.model.Order;
import archfixture.conforming.ordering.domain.model.OrderId;
import archfixture.conforming.ordering.domain.model.OrderRepository;
import archfixture.conforming.ordering.domain.model.OrderStatus;
import archfixture.conforming.shared.persistence.TableWriter;
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
 * <p>INSERT と UPDATE は shared の TableWriter で書く。
 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /** 保存した注文。 */
  private final Map<OrderId, Order> orders = new ConcurrentHashMap<>();

  /** 保存した注文の書き込みの結果。 */
  private final Map<OrderId, String> writeResults = new ConcurrentHashMap<>();

  /** 業務テーブルの INSERT と UPDATE の入口。 */
  private final TableWriter tableWriter;

  /** shared の入口を受け取る。 */
  /* package */ JooqOrderRepository(final TableWriter tableWriter) {
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
    writeResults.put(order.id(), tableWriter.insert("orders"));
  }

  @Override
  public void update(final Order order) {
    writeResults.put(order.id(), tableWriter.update("orders", order));
    orders.put(order.id(), order);
  }
}
