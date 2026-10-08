package archfixture.conforming.ordering.application;

import archfixture.conforming.ordering.OrderPlaced;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/** 注文の受付の期限が切れたことを受け取り、注文を取り消す。画面を介さないため、版を持たない Command を作る。 */
// 受信メソッドは on と命名し、トランザクション境界として public にする規約のため。
@SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
@Service
class OrderPlacedExpiryListener {

  /** 期限切れの注文を取り消す CommandHandler。 */
  private final CancelExpiredOrderCommandHandler cancelExpiredOrder;

  /** CommandHandler を受け取る。 */
  /* package */ OrderPlacedExpiryListener(
      final CancelExpiredOrderCommandHandler cancelExpiredOrder) {
    this.cancelExpiredOrder = cancelExpiredOrder;
  }

  /** イベントから Command を作り、CommandHandler へ渡す。 */
  @ApplicationModuleListener
  public void on(final OrderPlaced event) {
    cancelExpiredOrder.handle(new CancelExpiredOrderCommand(event.orderId()));
  }
}
