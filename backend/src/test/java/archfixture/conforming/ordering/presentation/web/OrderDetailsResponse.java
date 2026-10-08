package archfixture.conforming.ordering.presentation.web;

import archfixture.conforming.ordering.OrderDetails;

/** 注文の詳細を返す API の本文。 */
public record OrderDetailsResponse(String orderId, int lineCount) {

  /** クエリ結果から作る。 */
  public static OrderDetailsResponse from(final OrderDetails details) {
    return new OrderDetailsResponse(details.orderId(), details.lines().size());
  }
}
