package archfixture.violating.inventory.application;

import archfixture.violating.ordering.OrderPlaced;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 違反：applicationListenerIsNotImplemented（{@code @ApplicationModuleListener} の代わりに、{@code
 * ApplicationListener} を実装してイベントを受ける）。
 */
public class StockLevelWatcher
    implements ApplicationListener<PayloadApplicationEvent<OrderPlaced>> {

  /** 受け取った注文 ID。 */
  private final Queue<String> received = new ConcurrentLinkedQueue<>();

  /** 注文の受付を、発行した呼び出しの中で受ける。 */
  @Override
  public void onApplicationEvent(final PayloadApplicationEvent<OrderPlaced> event) {
    received.add(event.getPayload().orderId());
  }
}
