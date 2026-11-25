package com.example.demo.ordering.application;

import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderId;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 下書きの注文を取り消す。 */
@Service
public class CancelOrderCommandHandler {

  /** 注文を取り出して保存する Repository。 */
  private final OrderRepository orderRepository;

  /** 同じ冪等性キーの再送に、保存した Result を返す。 */
  private final Idempotency idempotency;

  /** 依存を受け取る。 */
  public CancelOrderCommandHandler(
      final OrderRepository orderRepository, final Idempotency idempotency) {
    this.orderRepository = orderRepository;
    this.idempotency = idempotency;
  }

  /** ロック番号を確かめて注文を取り消し、保存する。同じ冪等性キーの再送には、保存した Result を返す。 */
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
