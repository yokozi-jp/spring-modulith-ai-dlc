package archfixture.conforming.order.application;

import archfixture.conforming.order.domain.model.Order;
import archfixture.conforming.order.domain.model.OrderId;
import archfixture.conforming.order.domain.model.OrderRepository;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 期限切れの注文を取り消す。画面の版がないため ensureLockNo を呼ばない。 */
@Service
public class CancelExpiredOrderCommandHandler {

  /** 注文を取り出し、保存する Repository。 */
  private final OrderRepository orderRepository;

  /** 依存を受け取る。 */
  public CancelExpiredOrderCommandHandler(final OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 注文を取り消し、読み込んだ版で保存する。 */
  @Transactional
  public CancelExpiredOrderResult handle(final CancelExpiredOrderCommand command) {
    final Order order =
        orderRepository
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () -> new NoSuchElementException("order not found: orderId=" + command.orderId()));
    order.cancel();
    orderRepository.update(order);
    return new CancelExpiredOrderResult(order.id().value());
  }
}
