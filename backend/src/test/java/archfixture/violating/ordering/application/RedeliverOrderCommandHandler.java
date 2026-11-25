package archfixture.violating.ordering.application;

import archfixture.violating.shared.idempotency.Idempotency;

/**
 * 違反：idempotentCommandHandlersExecuteThroughIdempotency（キーを持つ Command を、保存した Result を返す入口に通さない）。
 */
public class RedeliverOrderCommandHandler {

  /** 同じキーの再送に、保存した Result を返す。handle が呼ばない。 */
  private final Idempotency idempotency;

  /** 依存を受け取る。 */
  public RedeliverOrderCommandHandler(final Idempotency idempotency) {
    this.idempotency = idempotency;
  }

  /** キーを見ずに注文を配送し直し、注文 ID を返す。 */
  public String handle(final RedeliverOrderCommand command) {
    return command.orderId();
  }

  /** handle の外で入口を呼んでも、規則を満たさない。 */
  /* package */ String redeliverOnce(final RedeliverOrderCommand command) {
    return idempotency.execute(command, String.class, command::orderId);
  }
}
