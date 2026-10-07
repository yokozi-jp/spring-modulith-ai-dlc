package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が static factory の呼び出しで作る、版のない
 * Command で保存する）。
 */
@Service
public class SuspendOrderCommandHandler {

  /** 停止した注文を保存する Repository。 */
  private final UnversionedOrderRepository suspendedOrderRepository;

  /** 停止した注文を保存する Repository を受け取る。 */
  public SuspendOrderCommandHandler(final UnversionedOrderRepository suspendedOrderRepository) {
    this.suspendedOrderRepository = suspendedOrderRepository;
  }

  /** 画面の版を持たずに注文を停止する。 */
  public void handle(final SuspendOrderCommand command) {
    suspendedOrderRepository.update(new OrderId(command.orderId()));
  }
}
