package archfixture.violating.ordering.application;

/** 違反フィクスチャが受け取る、注文の代金を請求するユースケースの入力。これ自体は規約どおり。 */
public record ChargeOrderCommand(String orderId) {}
