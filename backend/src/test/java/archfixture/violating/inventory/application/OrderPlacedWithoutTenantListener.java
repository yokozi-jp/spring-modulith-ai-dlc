package archfixture.violating.inventory.application;

import archfixture.violating.ordering.OrderPlacedWithoutTenant;
import archfixture.violating.ordering.application.ConfirmOrderCommand;
import archfixture.violating.ordering.application.ConfirmOrderCommandHandler;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/** 違反：moduleListenerEventsAreTenantScoped（引数のイベントが TenantScoped を実装しない）。 */
// 受信メソッドは on と命名し、トランザクション境界として public にする規約のため。
@SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
@Service
class OrderPlacedWithoutTenantListener {

  /** イベントを処理する CommandHandler。 */
  private final ConfirmOrderCommandHandler confirmOrder;

  /** イベントを処理する CommandHandler を受け取る。 */
  /* package */ OrderPlacedWithoutTenantListener(final ConfirmOrderCommandHandler confirmOrder) {
    this.confirmOrder = confirmOrder;
  }

  /** イベントを CommandHandler へ渡す。 */
  @ApplicationModuleListener
  public void on(final OrderPlacedWithoutTenant event) {
    confirmOrder.handle(new ConfirmOrderCommand(event.orderId()));
  }
}
