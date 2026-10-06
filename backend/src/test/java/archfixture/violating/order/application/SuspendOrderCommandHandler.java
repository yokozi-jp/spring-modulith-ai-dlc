package archfixture.violating.order.application;

import archfixture.violating.order.domain.model.OrderId;
import archfixture.violating.order.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が static factory で作る Command
 * で、版を持たずに保存する）。
 */
@Service
public class SuspendOrderCommandHandler {

  /** 注文を保存する Repository。 */
  private final UnversionedOrderRepository orderRepository;

  /** Repository を受け取る。 */
  public SuspendOrderCommandHandler(final UnversionedOrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 画面の版を持たずに注文を停止する。 */
  public void handle(final SuspendOrderCommand command) {
    orderRepository.update(new OrderId(command.orderId()));
  }
}
