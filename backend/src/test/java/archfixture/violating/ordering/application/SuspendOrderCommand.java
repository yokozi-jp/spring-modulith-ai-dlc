package archfixture.violating.ordering.application;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が static factory の呼び出しで作るのに
 * VersionedCommand ではない）。
 */
public record SuspendOrderCommand(String orderId) {

  /** 停止する注文 ID から Command を作る。 */
  public static SuspendOrderCommand from(final String orderId) {
    return new SuspendOrderCommand(orderId);
  }
}
