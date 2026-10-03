package archfixture.conforming.order.application;

import archfixture.conforming.order.OrderPlaced;
import archfixture.conforming.order.domain.model.CustomerId;
import archfixture.conforming.order.domain.model.Order;
import archfixture.conforming.order.domain.model.OrderId;
import archfixture.conforming.order.domain.model.OrderRepository;
import archfixture.conforming.order.domain.service.OrderLimitPolicy;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注文を受け付ける。 */
@Service
public class PlaceOrderCommandHandler {

  /** 注文を保存する Repository。 */
  private final OrderRepository orderRepository;

  /** 未出荷の注文の上限を確かめる Domain Service。 */
  private final OrderLimitPolicy orderLimitPolicy;

  /** イベントを発行する。 */
  private final ApplicationEventPublisher events;

  /** 受付時刻を取る時計。 */
  private final Clock clock;

  /** 依存を受け取る。 */
  public PlaceOrderCommandHandler(
      final OrderRepository orderRepository,
      final OrderLimitPolicy orderLimitPolicy,
      final ApplicationEventPublisher events,
      final Clock clock) {
    this.orderRepository = orderRepository;
    this.orderLimitPolicy = orderLimitPolicy;
    this.events = events;
    this.clock = clock;
  }

  /** 上限を確かめて注文を保存し、受付のイベントを発行する。 */
  @Transactional
  public PlaceOrderResult handle(final PlaceOrderCommand command) {
    final CustomerId customerId = new CustomerId(command.customerId());
    orderLimitPolicy.ensureCanPlace(customerId);
    final Order order = Order.place(OrderId.newId(), customerId, Instant.now(clock));
    orderRepository.add(order);
    events.publishEvent(new OrderPlaced(order.id().value(), customerId.value(), order.placedAt()));
    return new PlaceOrderResult(order.id().value());
  }
}
