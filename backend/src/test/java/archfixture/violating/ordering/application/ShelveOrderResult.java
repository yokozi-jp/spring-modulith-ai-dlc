package archfixture.violating.ordering.application;

/** 違反フィクスチャが返す、注文を保留にしたユースケースの出力。これ自体は規約どおり。 */
public record ShelveOrderResult(String orderId) {}
