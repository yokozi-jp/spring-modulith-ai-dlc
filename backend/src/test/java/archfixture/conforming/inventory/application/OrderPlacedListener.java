package archfixture.conforming.inventory.application;

import archfixture.conforming.order.OrderPlaced;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/** 注文の受付を受け取り、在庫の引き当てを始める。 */
// 受信メソッドは on と命名し、トランザクション境界として public にする規約のため。
@SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
@Service
class OrderPlacedListener {

  /** 在庫を引き当てる CommandHandler。 */
  private final ReserveStockCommandHandler reserveStock;

  /** 在庫を引き当てる CommandHandler を受け取る。 */
  /* package */ OrderPlacedListener(final ReserveStockCommandHandler reserveStock) {
    this.reserveStock = reserveStock;
  }

  /** イベントから Command を作り、CommandHandler へ渡す。 */
  @ApplicationModuleListener
  public void on(final OrderPlaced event) {
    reserveStock.handle(new ReserveStockCommand(event.orderId()));
  }
}
