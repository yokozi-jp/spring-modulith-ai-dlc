package archfixture.conforming.payment.application;

/** 注文の代金を請求するユースケースの入力。 */
public record ChargeOrderCommand(String customerId) {}
