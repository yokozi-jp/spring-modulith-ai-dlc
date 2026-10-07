package com.example.demo.payment.application;

import com.example.demo.order.OrderConfirmed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/** 注文の確定を受け取り、その注文の代金の請求を始める。 */
// 受信メソッドは on と命名し、トランザクション境界として public にする規約のため。
@SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
@Service
class OrderConfirmedListener {

  /** 注文の代金を請求する CommandHandler。 */
  private final ChargeOrderCommandHandler chargeOrder;

  /** 注文の代金を請求する CommandHandler を受け取る。 */
  /* package */ OrderConfirmedListener(final ChargeOrderCommandHandler chargeOrder) {
    this.chargeOrder = chargeOrder;
  }

  /** イベントから Command を作り、CommandHandler へ渡す。 */
  @ApplicationModuleListener
  public void on(final OrderConfirmed event) {
    chargeOrder.handle(new ChargeOrderCommand(event.orderId()));
  }
}
