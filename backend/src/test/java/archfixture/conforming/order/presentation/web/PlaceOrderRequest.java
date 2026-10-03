package archfixture.conforming.order.presentation.web;

import archfixture.conforming.order.application.PlaceOrderCommand;
import jakarta.validation.constraints.NotBlank;

/** 注文を受け付ける API の本文。 */
public record PlaceOrderRequest(@NotBlank String customerId) {

  /** Command へ変換する。 */
  public PlaceOrderCommand toCommand() {
    return new PlaceOrderCommand(customerId);
  }
}
