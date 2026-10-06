package archfixture.violating.shared.concurrency;

/**
 * 違反：commandsAndResultsAreApplicationRecords（VersionedCommand と同じ shared.concurrency に置いた、record
 * ではない Command）。
 */
public final class ForceUnlockCommand {

  /** ロックを外す注文 ID。 */
  private final String orderId;

  /** ロックを外す注文 ID を受け取る。 */
  public ForceUnlockCommand(final String orderId) {
    this.orderId = orderId;
  }

  /** ロックを外す注文 ID を返す。 */
  public String getOrderId() {
    return orderId;
  }
}
