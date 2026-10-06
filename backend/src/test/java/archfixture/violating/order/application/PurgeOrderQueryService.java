package archfixture.violating.order.application;

import archfixture.violating.order.domain.model.OrderId;
import archfixture.violating.order.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 違反：onlyCommandHandlersUpdateOrDeleteAggregates（QueryService が Repository の delete を呼ぶ）。 */
@Service
public class PurgeOrderQueryService {

  /** 注文を削除する Repository。 */
  private final UnversionedOrderRepository orderRepository;

  /** Repository を受け取る。 */
  public PurgeOrderQueryService(final UnversionedOrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 参照のふりをして注文を削除する。 */
  @Transactional(readOnly = true)
  public void purge(final String orderId) {
    orderRepository.delete(new OrderId(orderId));
  }
}
