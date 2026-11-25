package com.example.demo.ordering.application;

import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderId;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.ordering.domain.model.OrderedItem;
import com.example.demo.product.ProductQueries;
import com.example.demo.shared.failure.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 下書きの注文の明細を置き換える。 */
@Service
public class ChangeOrderLinesCommandHandler {

  /** 注文を取り出して保存する Repository。 */
  private final OrderRepository orderRepository;

  /** 明細の商品の単価と販売の状態を読む。 */
  private final ProductQueries productQueries;

  /** 依存を受け取る。 */
  public ChangeOrderLinesCommandHandler(
      final OrderRepository orderRepository, final ProductQueries productQueries) {
    this.orderRepository = orderRepository;
    this.productQueries = productQueries;
  }

  /** ロック番号を確かめ、明細の商品を読み直して明細を置き換え、保存する。 */
  @Transactional
  public ChangeOrderLinesResult handle(final ChangeOrderLinesCommand command) {
    final Order order =
        orderRepository
            .findById(new OrderId(UUID.fromString(command.orderId())))
            .orElseThrow(
                () -> new NotFoundException("order not found: orderId=" + command.orderId()));
    order.ensureLockNo(command.expectedLockNo());
    final List<OrderedItem> items =
        command.lines().stream()
            .map(line -> OrderedItems.item(line.productId(), line.quantity()))
            .toList();
    order.changeLines(items, OrderedItems.offers(productQueries, items));
    orderRepository.update(order);
    return new ChangeOrderLinesResult(order.id().value().toString());
  }
}
