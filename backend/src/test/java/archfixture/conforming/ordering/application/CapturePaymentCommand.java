package archfixture.conforming.ordering.application;

/** 注文の代金を請求するユースケースの入力。イベントを受けた Listener が作るため、版を持たない。 */
public record CapturePaymentCommand(String orderId) {}
