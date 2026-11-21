package archfixture.violating.ordering.application;

/** 違反フィクスチャが返す、注文の代金を請求したユースケースの出力。これ自体は規約どおり。 */
public record ChargeOrderResult(String paymentId) {}
