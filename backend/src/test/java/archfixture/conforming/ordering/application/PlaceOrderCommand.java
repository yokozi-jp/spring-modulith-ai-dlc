package archfixture.conforming.ordering.application;

import archfixture.conforming.shared.idempotency.IdempotencyKey;

/** 注文を受け付けるユースケースの入力。 */
public record PlaceOrderCommand(String customerId, IdempotencyKey idempotencyKey) {}
