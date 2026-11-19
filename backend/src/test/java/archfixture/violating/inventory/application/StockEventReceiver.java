package archfixture.violating.inventory.application;

import archfixture.violating.ordering.OrderPlaced;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 違反：eventListenersAreApplicationModuleListeners（{@code @ApplicationModuleListener}
 * の代わりに、{@code @EventListener} と {@code @TransactionalEventListener} でイベントを受ける）。
 */
public class StockEventReceiver {

  /** 受け取った注文 ID。 */
  private final Queue<String> received = new ConcurrentLinkedQueue<>();

  /** 注文の受付を、発行側のトランザクションの中で受ける。 */
  @EventListener
  public void onPlaced(final OrderPlaced event) {
    received.add(event.orderId());
  }

  /** 注文の受付を、発行側のコミットの後に受ける。 */
  @TransactionalEventListener
  public void afterPlaced(final OrderPlaced event) {
    received.add(event.orderId());
  }
}
