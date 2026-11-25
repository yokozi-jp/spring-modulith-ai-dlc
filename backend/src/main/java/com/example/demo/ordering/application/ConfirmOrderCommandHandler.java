package com.example.demo.ordering.application;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderId;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.security.NoRoleRequired;
import com.example.demo.shared.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 下書きの注文を確定する。 */
@Service
public class ConfirmOrderCommandHandler {

  /** 注文を取り出して保存する Repository。 */
  private final OrderRepository orderRepository;

  /** イベントを発行する。 */
  private final ApplicationEventPublisher events;

  /** 確定した時刻を取る時計。 */
  private final Clock clock;

  /** 依存を受け取る。 */
  public ConfirmOrderCommandHandler(
      final OrderRepository orderRepository,
      final ApplicationEventPublisher events,
      final Clock clock) {
    this.orderRepository = orderRepository;
    this.events = events;
    this.clock = clock;
  }

  /** ロック番号を確かめて注文を確定して保存し、確定のイベントを発行する。 */
  @NoRoleRequired(reason = "注文の操作はテナントの全員に許す。見える範囲は行レベルセキュリティでテナントに絞る。持ち主で絞るのは #188 で足す")
  @Transactional
  public ConfirmOrderResult handle(final ConfirmOrderCommand command) {
    final Order order =
        orderRepository
            .findById(new OrderId(UUID.fromString(command.orderId())))
            .orElseThrow(
                () -> new NotFoundException("order not found: orderId=" + command.orderId()));
    order.ensureLockNo(command.expectedLockNo());
    order.confirm();
    orderRepository.update(order);
    events.publishEvent(
        new OrderConfirmed(
            order.id().value().toString(), Instant.now(clock), TenantContext.current()));
    return new ConfirmOrderResult(order.id().value().toString());
  }
}
