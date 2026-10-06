package archfixture.conforming.order.domain.service;

import archfixture.conforming.order.domain.model.CustomerId;
import archfixture.conforming.order.domain.model.OrderRepository;
import archfixture.conforming.shared.failure.BusinessRuleViolationException;
import org.springframework.stereotype.Service;

/** 「未出荷の注文は 3 件まで」という、複数の注文にまたがる業務規則。 */
@Service
public class OrderLimitPolicy {

  /** 顧客 1 人あたりの未出荷の注文の上限。 */
  private static final long MAX_UNSHIPPED_ORDERS = 3;

  /** 未出荷の注文を数える Repository。 */
  private final OrderRepository orderRepository;

  /** 未出荷の注文を数える Repository を受け取る。 */
  public OrderLimitPolicy(final OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 顧客が新しい注文を出せることを確かめる。 */
  public void ensureCanPlace(final CustomerId customerId) {
    if (orderRepository.countUnshippedByCustomer(customerId) >= MAX_UNSHIPPED_ORDERS) {
      throw new BusinessRuleViolationException(
          "unshipped order limit reached: customerId=" + customerId.value());
    }
  }
}
