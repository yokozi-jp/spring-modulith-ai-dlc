package archfixture.conforming.order;

import java.util.List;

/** 注文の詳細。 */
public record OrderDetails(String orderId, List<OrderDetails.Line> lines) {

  /** 明細を変更できないリストとして持つ。 */
  public OrderDetails {
    lines = List.copyOf(lines);
  }

  /** 注文明細の 1 行。 */
  public record Line(int lineNumber, String productCode) {}
}
