package archfixture.conforming.order.application;

/** 画面から注文を取り消すユースケースの入力。画面が表示した版を持つ。 */
public record CancelOrderCommand(String orderId, long lockNo) {}
