package archfixture.violating.order;

/** 違反フィクスチャが受け取るイベント。これ自体は規約どおり。 */
public record OrderPlaced(String orderId) {}
