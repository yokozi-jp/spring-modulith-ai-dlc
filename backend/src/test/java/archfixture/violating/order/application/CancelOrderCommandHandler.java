package archfixture.violating.order.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 違反：commandHandlersExposeOnlyTransactionalHandle（public execute を持ち @Transactional がない）。 */
@Service
public class CancelOrderCommandHandler {

  /** 取り消した注文 ID。 */
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

  /** 注文を取り消し、その注文 ID を返す。 */
  public String execute(final String orderId) {
    cancelled.add(orderId);
    return orderId;
  }
}
