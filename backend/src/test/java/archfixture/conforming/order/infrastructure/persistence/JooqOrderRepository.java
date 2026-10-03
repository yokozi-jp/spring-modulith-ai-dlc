package archfixture.conforming.order.infrastructure.persistence;

import archfixture.conforming.order.domain.model.CustomerId;
import archfixture.conforming.order.domain.model.Order;
import archfixture.conforming.order.domain.model.OrderId;
import archfixture.conforming.order.domain.model.OrderRepository;
import archfixture.conforming.order.domain.model.OrderStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 注文の Repository の実装。フィクスチャなので jOOQ の代わりに Map へ保存する。 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /** 保存した注文。 */
  private final Map<OrderId, Order> orders = new ConcurrentHashMap<>();

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
  public void save(final Order order) {
    orders.put(order.id(), order);
  }
}
