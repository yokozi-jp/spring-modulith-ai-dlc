package archfixture.violating.ordering.application;

import org.springframework.scheduling.annotation.Async;

/** 違反：asyncMethodsReturnVoidOrFuture（@Async のメソッドが String を返す）。 */
public class OrderNotifier {

  /** 出荷を通知し、通知の ID を返す。 */
  @Async
  public String notifyShipped(final String orderId) {
    return "notice-" + orderId;
  }
}
