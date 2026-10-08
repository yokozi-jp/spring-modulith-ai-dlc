package archfixture.violating.ordering.application;

/** 違反：commandsAndResultsAreApplicationRecords（Command を record ではなく class にする）。 */
public final class ConfirmOrderCommand {

  /** 確定する注文 ID。 */
  private final String orderId;

  /** 確定する注文 ID を受け取る。 */
  public ConfirmOrderCommand(final String orderId) {
    this.orderId = orderId;
  }

  /** 確定する注文 ID を返す。 */
  public String getOrderId() {
    return orderId;
  }
}
