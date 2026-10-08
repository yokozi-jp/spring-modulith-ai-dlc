package archfixture.conforming.ordering.application;

/** 注文を受け付けるユースケースの入力。 */
public record PlaceOrderCommand(String customerId) {}
