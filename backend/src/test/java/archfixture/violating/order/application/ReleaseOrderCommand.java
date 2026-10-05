package archfixture.violating.order.application;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が作るのに VersionedCommand ではない）。
 */
public record ReleaseOrderCommand(String orderId) {}
