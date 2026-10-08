package archfixture.violating.inventory.application;

import archfixture.violating.ordering.OrderPlaced;
import archfixture.violating.ordering.application.ConfirmOrderCommand;
import archfixture.violating.ordering.application.ConfirmOrderCommandHandler;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/** 違反：listenersExposeOnlyOnAndCallOneCommandHandler（on のほかに public の replay を持つ）。 */
@SuppressWarnings("PMD.ShortMethodName")
@Service
public class OrderPlacedListener {

  /** イベントを処理する CommandHandler。 */
  private final ConfirmOrderCommandHandler confirmOrder;

  /** イベントを処理する CommandHandler を受け取る。 */
  public OrderPlacedListener(final ConfirmOrderCommandHandler confirmOrder) {
    this.confirmOrder = confirmOrder;
  }

  /** イベントを CommandHandler へ渡す。 */
  @ApplicationModuleListener
  public void on(final OrderPlaced event) {
    replay(event.orderId());
  }

  /** 注文 ID を指定して処理をやり直す。 */
  public void replay(final String orderId) {
    confirmOrder.handle(new ConfirmOrderCommand(orderId));
  }
}
