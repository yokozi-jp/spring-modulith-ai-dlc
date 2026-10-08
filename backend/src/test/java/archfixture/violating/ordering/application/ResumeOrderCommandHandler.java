package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.UnversionedOrderRepository;
import org.springframework.stereotype.Service;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（static factory のメソッド参照で presentation が作る、版のない
 * Command で削除する）。
 */
@Service
public class ResumeOrderCommandHandler {

  /** 再開の前に古い注文を削除する Repository。 */
  private final UnversionedOrderRepository staleOrderRepository;

  /** 古い注文を削除する Repository を受け取る。 */
  public ResumeOrderCommandHandler(final UnversionedOrderRepository staleOrderRepository) {
    this.staleOrderRepository = staleOrderRepository;
  }

  /** 画面の版を持たずに、再開の前に古い注文を削除し、その注文 ID を返す。 */
  public OrderId handle(final ResumeOrderCommand command) {
    final OrderId id = new OrderId(command.orderId());
    staleOrderRepository.delete(id);
    return id;
  }
}
