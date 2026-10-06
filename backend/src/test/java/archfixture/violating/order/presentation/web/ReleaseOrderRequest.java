package archfixture.violating.order.presentation.web;

import archfixture.violating.order.application.ReleaseOrderCommand;
import archfixture.violating.order.application.ResumeOrderCommand;
import archfixture.violating.order.application.SuspendOrderCommand;
import java.util.function.Function;

/**
 * 違反：commandsBuiltByPresentationForWritesAreVersioned（版を持たない Command を、コンストラクタ、static factory
 * の呼び出し、static factory のメソッド参照で作る Request）。
 */
public record ReleaseOrderRequest(String orderId) {

  /** 版を持たない Command へ変換する。 */
  public ReleaseOrderCommand toCommand() {
    return new ReleaseOrderCommand(orderId);
  }

  /** 版を持たない Command へ、static factory の呼び出しで変換する。 */
  public SuspendOrderCommand toSuspendCommand() {
    return SuspendOrderCommand.from(orderId);
  }

  /** 版を持たない Command へ、static factory のメソッド参照で変換する。 */
  public ResumeOrderCommand toResumeCommand() {
    final Function<String, ResumeOrderCommand> factory = ResumeOrderCommand::from;
    return factory.apply(orderId);
  }
}
