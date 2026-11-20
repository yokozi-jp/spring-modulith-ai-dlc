package archfixture.violating.ordering.application;

/** 違反フィクスチャが受け取る、注文を保留にするユースケースの入力。これ自体は規約どおり。 */
public record ShelveOrderCommand(String orderId) {}
