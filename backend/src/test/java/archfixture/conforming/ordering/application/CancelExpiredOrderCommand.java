package archfixture.conforming.ordering.application;

/** 期限切れの注文を取り消すユースケースの入力。画面を介さないため、版を持たない。 */
public record CancelExpiredOrderCommand(String orderId) {}
