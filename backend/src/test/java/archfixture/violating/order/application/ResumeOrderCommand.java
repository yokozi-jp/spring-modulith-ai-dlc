package archfixture.violating.order.application;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（presentation が static factory のメソッド参照で作るのに
 * VersionedCommand ではない）。
 */
public record ResumeOrderCommand(String orderId) {

  /** 再開する注文 ID から Command を作る。 */
  public static ResumeOrderCommand from(final String orderId) {
    return new ResumeOrderCommand(orderId);
  }
}
