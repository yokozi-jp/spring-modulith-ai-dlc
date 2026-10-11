package archfixture.violating.inventory.application;

import archfixture.violating.ordering.OrderSummary;
import java.util.List;

/** 違反：queryResultStatusIsNotReadByOtherModules（注文モジュールの状態の名前を比べて分岐する）。 */
public final class OrderStatusGuard {

  private OrderStatusGuard() {}

  /** 状態の名前の呼び出しで、確定した注文かを判断する。 */
  public static boolean isConfirmed(final OrderSummary summary) {
    return "CONFIRMED".equals(summary.status());
  }

  /** 名前が Status で終わる accessor のメソッド参照で、支払い済みの注文を選ぶ。 */
  public static List<String> paidStatuses(final List<OrderSummary> summaries) {
    return summaries.stream().map(OrderSummary::paymentStatus).filter("PAID"::equals).toList();
  }
}
