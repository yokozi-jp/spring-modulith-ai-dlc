package archfixture.violating.ordering;

/** 違反フィクスチャが状態の名前を読む参照の結果。これ自体は規約どおり。 */
public record OrderSummary(String orderId, String status, String paymentStatus) {}
