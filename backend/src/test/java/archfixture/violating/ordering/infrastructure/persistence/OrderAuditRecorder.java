package archfixture.violating.ordering.infrastructure.persistence;

import archfixture.violating.ordering.OrderPlaced;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 違反：transactionalMethodsArePublicApplicationMethods と moduleListenersAreApplicationListeners（イベントを
 * infrastructure で受け取る）。
 */
@SuppressWarnings("PMD.ShortMethodName")
@Component
public class OrderAuditRecorder {

  /** 受け取った注文 ID。 */
  private final Queue<String> received = new ConcurrentLinkedQueue<>();

  /** 受け取った注文 ID を記録する。 */
  @ApplicationModuleListener
  public void on(final OrderPlaced event) {
    received.add(event.orderId());
  }
}
