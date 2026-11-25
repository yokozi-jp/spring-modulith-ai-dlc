package archfixture.conforming.ordering.application;

import archfixture.conforming.ordering.domain.model.Order;
import archfixture.conforming.ordering.domain.model.OrderId;
import archfixture.conforming.ordering.domain.model.OrderRepository;
import archfixture.conforming.shared.failure.NotFoundException;
import archfixture.conforming.shared.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 画面から注文を取り消す。 */
@Service
public class CancelOrderCommandHandler {

  /** 注文を取り出し、保存する Repository。 */
  private final OrderRepository orderRepository;

  /** 同じキーの再送に、保存した Result を返す。 */
  private final Idempotency idempotency;

  /** 依存を受け取る。 */
  public CancelOrderCommandHandler(
      final OrderRepository orderRepository, final Idempotency idempotency) {
    this.orderRepository = orderRepository;
    this.idempotency = idempotency;
  }

  /**
   * 画面の版を確かめてから注文を取り消し、保存する。
   *
   * <p>版の確かめは、handle が呼ぶ private メソッドの中に置いても R4 を満たす。
   */
  @Transactional
  public CancelOrderResult handle(final CancelOrderCommand command) {
    return idempotency.execute(command, CancelOrderResult.class, () -> cancel(command));
  }

  private CancelOrderResult cancel(final CancelOrderCommand command) {
    final Order order =
        orderRepository
            .findById(new OrderId(UUID.fromString(command.orderId())))
            .orElseThrow(
                () -> new NotFoundException("order not found: orderId=" + command.orderId()));
    order.ensureLockNo(command.expectedLockNo());
    order.cancel();
    orderRepository.update(order);
    return new CancelOrderResult(order.id().value().toString());
  }
}
