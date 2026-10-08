package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;

/** 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が作る Command で保存するのに、版を持たない）。 */
@Service
public class ReleaseOrderCommandHandler {

  /** 注文を保存する Repository。 */
  private final UnversionedOrderRepository orderRepository;

  /** Repository を受け取る。 */
  public ReleaseOrderCommandHandler(final UnversionedOrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 画面の版を持たずに注文を保存し、その注文 ID を返す。 */
  public String handle(final ReleaseOrderCommand command) {
    orderRepository.update(new OrderId(command.orderId()));
    return command.orderId();
  }
}
