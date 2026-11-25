package archfixture.conforming.ordering.application;

import archfixture.conforming.ordering.OrderPlaced;
import archfixture.conforming.ordering.domain.model.CustomerId;
import archfixture.conforming.ordering.domain.model.Order;
import archfixture.conforming.ordering.domain.model.OrderRepository;
import archfixture.conforming.ordering.domain.service.OrderLimitPolicy;
import archfixture.conforming.shared.idempotency.Idempotency;
import archfixture.conforming.shared.tenancy.TenantContext;
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

  /** 同じキーの再送に、保存した Result を返す。 */
  private final Idempotency idempotency;

  /** 依存を受け取る。 */
  public PlaceOrderCommandHandler(
      final OrderRepository orderRepository,
      final OrderLimitPolicy orderLimitPolicy,
      final ApplicationEventPublisher events,
      final Clock clock,
      final Idempotency idempotency) {
    this.orderRepository = orderRepository;
    this.orderLimitPolicy = orderLimitPolicy;
    this.events = events;
    this.clock = clock;
    this.idempotency = idempotency;
  }

  /** 上限を確かめて注文を保存し、受付のイベントを発行する。同じキーの再送には、保存した Result を返す。 */
  @Transactional
  public PlaceOrderResult handle(final PlaceOrderCommand command) {
    return idempotency.execute(command, PlaceOrderResult.class, () -> place(command));
  }

  private PlaceOrderResult place(final PlaceOrderCommand command) {
    final CustomerId customerId = new CustomerId(command.customerId());
    orderLimitPolicy.ensureCanPlace(customerId);
    final Order order = Order.place(orderRepository.nextId(), customerId, Instant.now(clock));
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
