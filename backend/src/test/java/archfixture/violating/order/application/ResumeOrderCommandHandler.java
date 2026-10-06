package archfixture.violating.order.application;

import archfixture.violating.order.domain.model.OrderId;
import archfixture.violating.order.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が static factory のメソッド参照で作る
 * Command で、版を持たずに削除する）。
 */
@Service
public class ResumeOrderCommandHandler {

  /** 注文を削除する Repository。 */
  private final UnversionedOrderRepository orderRepository;

  /** Repository を受け取る。 */
  public ResumeOrderCommandHandler(final UnversionedOrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 画面の版を持たずに、再開の前に古い注文を削除し、その注文 ID を返す。 */
  public OrderId handle(final ResumeOrderCommand command) {
    final OrderId id = new OrderId(command.orderId());
    orderRepository.delete(id);
    return id;
  }
}
