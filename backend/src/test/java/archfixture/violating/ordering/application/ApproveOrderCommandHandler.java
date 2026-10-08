package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.Order;
import archfixture.violating.ordering.domain.model.OrderId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 違反：commandHandlersEnsureScreenLockNo（VersionedCommand を受け取るのに、handle では ensureLockNo を呼ばず、使わない
 * private メソッドだけが呼ぶ）。
 */
@Service
public class ApproveOrderCommandHandler {

  /** 承認した注文 ID。 */
  private final Set<String> approved = ConcurrentHashMap.newKeySet();

  /** 画面の版を比べずに注文を承認し、その注文 ID を返す。 */
  public String handle(final ApproveOrderCommand command) {
    approved.add(command.orderId());
    return command.orderId();
  }

  // 違反の形を作るため、handle から呼ばない private メソッドだけが ensureLockNo を呼ぶ。
  @SuppressWarnings({"PMD.UnusedPrivateMethod", "UnusedMethod"})
  private void ensureScreenLockNo(final ApproveOrderCommand command) {
    new Order(new OrderId(command.orderId()), 1L).ensureLockNo(command.expectedLockNo());
  }
}
