package archfixture.conforming.ordering.application;

import archfixture.conforming.ordering.OrderDetails;
import archfixture.conforming.ordering.OrderQueries;
import archfixture.conforming.ordering.OrderSearchCriteria;
import archfixture.conforming.ordering.OrderSummary;
import archfixture.conforming.ordering.domain.model.CustomerId;
import archfixture.conforming.ordering.domain.model.OrderId;
import archfixture.conforming.ordering.domain.model.OrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注文の読み取り窓口を実装する。 */
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
        .map(order -> new OrderDetails(order.id().value().toString(), List.of()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<OrderSummary> search(final OrderSearchCriteria criteria) {
    return orderRepository.findByCustomer(new CustomerId(criteria.customerId())).stream()
        .map(order -> new OrderSummary(order.id().value().toString(), order.status().name()))
        .toList();
  }
}
