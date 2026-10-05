package archfixture.violating.order.domain.service;

import archfixture.violating.order.domain.model.OrderId;
import archfixture.violating.order.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;

/** 違反：onlyCommandHandlersUpdateOrDeleteAggregates（Domain Service が Repository の update を呼ぶ）。 */
@Service
public class ReopenPolicy {

  /** 注文を保存する Repository。 */
  private final UnversionedOrderRepository orderRepository;

  /** Repository を受け取る。 */
  public ReopenPolicy(final UnversionedOrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 注文を再開して保存する。 */
  public void reopen(final OrderId orderId) {
    orderRepository.update(orderId);
  }
}
