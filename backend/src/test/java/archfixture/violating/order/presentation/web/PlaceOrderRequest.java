package archfixture.violating.order.presentation.web;

/** 違反：requestsAndResponsesArePresentationWebRecords（Request を record ではなく class にする）。 */
public class PlaceOrderRequest {

  /** 注文する顧客の ID。 */
  private final String customerId;

  /** 注文する顧客の ID を受け取る。 */
  public PlaceOrderRequest(final String customerId) {
    this.customerId = customerId;
  }

  /** 注文する顧客の ID を返す。 */
  public String getCustomerId() {
    return customerId;
  }
}
