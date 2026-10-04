package archfixture.violating.order.application;

/** 違反フィクスチャが受け取る、画面が表示した版を持つ Command。これ自体は規約どおり。 */
public record ApproveOrderCommand(String orderId, long lockNo) {}
