package archfixture.conforming.ordering.application;

import archfixture.conforming.ordering.OrderPlaced;
import archfixture.conforming.ordering.domain.model.CustomerId;
import archfixture.conforming.ordering.domain.model.Order;
import archfixture.conforming.ordering.domain.model.OrderRepository;
import archfixture.conforming.ordering.domain.service.OrderLimitPolicy;
import archfixture.conforming.shared.tenant.TenantContext;
import archfixture.conforming.shared.time.UseCaseTime;
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

  /** 依存を受け取る。 */
  public PlaceOrderCommandHandler(
      final OrderRepository orderRepository,
      final OrderLimitPolicy orderLimitPolicy,
      final ApplicationEventPublisher events) {
    this.orderRepository = orderRepository;
    this.orderLimitPolicy = orderLimitPolicy;
    this.events = events;
  }

  /** 上限を確かめて注文を保存し、受付のイベントを発行する。 */
  @Transactional
  public PlaceOrderResult handle(final PlaceOrderCommand command) {
    final CustomerId customerId = new CustomerId(command.customerId());
    orderLimitPolicy.ensureCanPlace(customerId);
    final Order order = Order.place(orderRepository.nextId(), customerId, UseCaseTime.now());
    orderRepository.add(order);
    events.publishEvent(
        new OrderPlaced(
            order.id().value().toString(),
            customerId.value(),
            order.placedAt(),
            TenantContext.current()));
    return new PlaceOrderResult(order.id().value().toString());
  }
}
