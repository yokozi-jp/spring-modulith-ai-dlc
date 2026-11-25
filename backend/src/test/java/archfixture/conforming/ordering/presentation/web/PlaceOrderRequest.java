package archfixture.conforming.ordering.presentation.web;

import archfixture.conforming.ordering.application.PlaceOrderCommand;
import archfixture.conforming.shared.idempotency.IdempotencyKey;
import jakarta.validation.constraints.NotBlank;

/** 注文を受け付ける API の本文。 */
public record PlaceOrderRequest(@NotBlank String customerId) {

  /** ヘッダーのキーと合わせて Command へ変換する。 */
  public PlaceOrderCommand toCommand(final IdempotencyKey idempotencyKey) {
    return new PlaceOrderCommand(customerId, idempotencyKey);
  }
}
