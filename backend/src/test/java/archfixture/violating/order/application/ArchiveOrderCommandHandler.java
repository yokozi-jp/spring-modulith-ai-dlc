package archfixture.violating.order.application;

import archfixture.violating.order.domain.model.Order;
import archfixture.violating.order.domain.model.OrderId;
import archfixture.violating.order.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が作る版のない Command で、update ではなく
 * save という名前の書き込みを呼ぶ）。
 */
@Service
public class ArchiveOrderCommandHandler {

  /** 注文を保存する Repository。 */
  private final UnversionedOrderRepository orderRepository;

  /** Repository を受け取る。 */
  public ArchiveOrderCommandHandler(final UnversionedOrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 画面の版を持たずに、注文を save で保存する。 */
  public void handle(final ReleaseOrderCommand command) {
    orderRepository.save(new Order(new OrderId(command.orderId()), 1L));
  }
}
