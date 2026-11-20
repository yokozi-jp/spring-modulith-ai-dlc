package archfixture.violating.ordering.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 違反：commandHandlersAndListenersDoNotImplementInterfaces（{@link ShelveOrderUseCase} を実装する）。 */
@Service
public class ShelveOrderCommandHandler implements ShelveOrderUseCase {

  @Override
  @Transactional
  public ShelveOrderResult handle(final ShelveOrderCommand command) {
    return new ShelveOrderResult(command.orderId());
  }
}
