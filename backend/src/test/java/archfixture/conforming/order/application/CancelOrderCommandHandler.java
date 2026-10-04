package archfixture.conforming.order.application;

import archfixture.conforming.order.domain.model.Order;
import archfixture.conforming.order.domain.model.OrderId;
import archfixture.conforming.order.domain.model.OrderRepository;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 画面から注文を取り消す。 */
@Service
public class CancelOrderCommandHandler {

  /** 注文を取り出し、保存する Repository。 */
  private final OrderRepository orderRepository;

  /** 依存を受け取る。 */
  public CancelOrderCommandHandler(final OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 画面の版を確かめてから注文を取り消し、保存する。 */
  @Transactional
  public CancelOrderResult handle(final CancelOrderCommand command) {
    final Order order =
        orderRepository
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () -> new NoSuchElementException("order not found: orderId=" + command.orderId()));
    order.ensureLockNo(command.lockNo());
    order.cancel();
    orderRepository.update(order);
    return new CancelOrderResult(order.id().value());
  }
}
