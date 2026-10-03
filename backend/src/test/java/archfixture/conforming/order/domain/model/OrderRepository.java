package archfixture.conforming.order.domain.model;

import java.util.List;
import java.util.Optional;

/** 注文の集約を保存し、取り出す。 */
public interface OrderRepository {

  /** ID で注文を探す。 */
  Optional<Order> findById(OrderId id);

  /** 顧客の注文を返す。 */
  List<Order> findByCustomer(CustomerId customerId);

  /** 顧客の未出荷の注文を数える。 */
  long countUnshippedByCustomer(CustomerId customerId);

  /** 注文を保存する。 */
  void save(Order order);
}
