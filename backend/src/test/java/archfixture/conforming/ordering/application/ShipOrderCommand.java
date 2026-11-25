package archfixture.conforming.ordering.application;

/** 注文を出荷するユースケースの入力。管理者の画面から送るが、注文を書き換えないため版を持たない。 */
public record ShipOrderCommand(String orderId) {}
