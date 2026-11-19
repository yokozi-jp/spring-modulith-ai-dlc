package archfixture.conforming.ordering.application;

import archfixture.conforming.ordering.OrderPlaced;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/** 注文の受付を受け取り、その注文の代金の請求を始める。 */
// 受信メソッドは on と命名し、トランザクション境界として public にする規約のため。
@SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
@Service
class OrderPlacedPaymentListener {

  /** 外部システムを呼ぶ CommandHandler。 */
  private final CapturePaymentCommandHandler capturePayment;

  /** CommandHandler を受け取る。 */
  /* package */ OrderPlacedPaymentListener(final CapturePaymentCommandHandler capturePayment) {
    this.capturePayment = capturePayment;
  }

  /** イベントから Command を作り、CommandHandler へ渡す。 */
  @ApplicationModuleListener
  public void on(final OrderPlaced event) {
    capturePayment.handle(new CapturePaymentCommand(event.orderId()));
  }
}
