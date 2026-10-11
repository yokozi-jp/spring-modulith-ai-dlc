package archfixture.conforming.payment.application;

import java.util.List;

/** 注文の代金を請求したユースケースの出力。 */
public record ChargeOrderResult(List<String> orderStatuses) {

  /** 状態の名前を変更できないリストとして持つ。 */
  public ChargeOrderResult {
    orderStatuses = List.copyOf(orderStatuses);
  }
}
