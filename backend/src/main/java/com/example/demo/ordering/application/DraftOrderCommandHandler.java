package com.example.demo.ordering.application;

import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.ordering.domain.model.OrderedItem;
import com.example.demo.product.ProductQueries;
import com.example.demo.shared.idempotency.Idempotency;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 下書きの注文を作る。 */
@Service
public class DraftOrderCommandHandler {

  /** 注文を保存する Repository。 */
  private final OrderRepository orderRepository;

  /** 明細の商品の単価と販売の状態を読む。 */
  private final ProductQueries productQueries;

  /** 作成した時刻を取る時計。 */
  private final Clock clock;

  /** 同じ冪等性キーの再送に、保存した Result を返す。 */
  private final Idempotency idempotency;

  /** 依存を受け取る。 */
  public DraftOrderCommandHandler(
      final OrderRepository orderRepository,
      final ProductQueries productQueries,
      final Clock clock,
      final Idempotency idempotency) {
    this.orderRepository = orderRepository;
    this.productQueries = productQueries;
    this.clock = clock;
    this.idempotency = idempotency;
  }

  /** 明細の商品を読んで下書きの注文を作り、保存する。同じ冪等性キーの再送には、保存した Result を返す。 */
  @Transactional
  public DraftOrderResult handle(final DraftOrderCommand command) {
    return idempotency.execute(command, DraftOrderResult.class, () -> draft(command));
  }

  private DraftOrderResult draft(final DraftOrderCommand command) {
    final List<OrderedItem> items =
        command.lines().stream()
            .map(line -> OrderedItems.item(line.productId(), line.quantity()))
            .toList();
    final Order order =
        Order.draft(
            command.customerOrderCode(),
            items,
            OrderedItems.offers(productQueries, items),
            Instant.now(clock));
    orderRepository.add(order);
    return new DraftOrderResult(order.id().value().toString());
  }
}
