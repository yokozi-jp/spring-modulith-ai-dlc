package archfixture.violating.order.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 違反：commandHandlersEnsureScreenLockNo（Command が lockNo を持つのに ensureLockNo を呼ばない）。 */
@Service
public class ApproveOrderCommandHandler {

  /** 承認した注文 ID。 */
  private final Set<String> approved = ConcurrentHashMap.newKeySet();

  /** 画面の版を比べずに注文を承認し、その注文 ID を返す。 */
  public String handle(final ApproveOrderCommand command) {
    approved.add(command.orderId());
    return command.orderId();
  }
}
