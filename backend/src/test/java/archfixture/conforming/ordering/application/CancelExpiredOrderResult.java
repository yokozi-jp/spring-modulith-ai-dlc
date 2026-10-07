package archfixture.conforming.ordering.application;

/** 期限切れの注文を取り消したユースケースの出力。 */
public record CancelExpiredOrderResult(String orderId) {}
