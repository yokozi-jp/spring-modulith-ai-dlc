package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.Order;
import archfixture.violating.ordering.domain.model.OrderId;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * commandHandlersEnsureScreenLockNo が、ラムダの中の ensureLockNo を handle の呼び出しとして数えて満たすことを確かめる。
 *
 * <p>{@code Optional.ifPresent} のラムダは実行されないことがあるが、規則はそれを見ないため、違反として検出しない（ADR-054 の Negative）。
 */
@Service
public class LambdaEnsureCommandHandler {

  /** ラムダの中で版を比べて、注文 ID を返す。 */
  public String handle(final ApproveOrderCommand command) {
    final Order order = new Order(new OrderId(command.orderId()), 1L);
    Optional.ofNullable(order).ifPresent(target -> target.ensureLockNo(command.expectedLockNo()));
    return order.id().value();
  }
}
