package com.example.demo.ordering.application;

import com.example.demo.ordering.OrderDetails;
import com.example.demo.ordering.OrderQueries;
import com.example.demo.ordering.OrderSearchCriteria;
import com.example.demo.ordering.OrderSummary;
import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderId;
import com.example.demo.ordering.domain.model.OrderRepository;
import com.example.demo.ordering.domain.model.OrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注文の参照を、Repository で読んだ集約から作る。 */
@Service
class OrderQueryService implements OrderQueries {

  /** 注文を取り出す Repository。 */
  private final OrderRepository orderRepository;

  /** 注文を取り出す Repository を受け取る。 */
  /* package */ OrderQueryService(final OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OrderDetails> findDetails(final String orderId) {
    return orderRepository
        .findById(new OrderId(UUID.fromString(orderId)))
        .map(OrderQueryService::toDetails);
  }

  @Override
  @Transactional(readOnly = true)
  public List<OrderSummary> search(final OrderSearchCriteria criteria) {
    // ponytail: ページングしない。参照業務機能の注文の件数は少ない。数百件を超えるなら ADR-013 のカーソルを足す。
    final @Nullable String status = criteria.status();
    final List<Order> orders =
        status == null
            ? orderRepository.findAll()
            : orderRepository.findByStatus(OrderStatus.valueOf(status));
    return orders.stream().map(OrderQueryService::toSummary).toList();
  }

  private static OrderSummary toSummary(final Order order) {
    return new OrderSummary(
        order.id().value().toString(),
        order.customerOrderCode(),
        order.status().name(),
        order.orderedAt(),
        order.total().amount(),
        order.lockNo());
  }

  private static OrderDetails toDetails(final Order order) {
    return new OrderDetails(
        order.id().value().toString(),
        order.customerOrderCode(),
        order.status().name(),
        order.status() == OrderStatus.CONFIRMED,
        order.orderedAt(),
        order.total().amount(),
        order.lockNo(),
        order.lines().stream()
            .map(
                line ->
                    new OrderDetails.Line(
                        line.lineNumber(),
                        line.productId().value().toString(),
                        line.quantity().value(),
                        line.unitPrice().amount(),
                        line.amount().amount()))
            .toList());
  }
}
