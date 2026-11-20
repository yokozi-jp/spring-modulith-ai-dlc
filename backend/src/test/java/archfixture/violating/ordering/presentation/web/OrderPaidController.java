package archfixture.violating.ordering.presentation.web;

import archfixture.violating.ordering.application.OrderPaidListener;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：listenersAreNotCalled（Controller が Listener の on を直接呼ぶ）。 */
@RestController
@RequestMapping("/api/order-paid")
class OrderPaidController {

  /** 呼んではいけない Listener。 */
  private final OrderPaidListener listener;

  /** 依存を受け取る。 */
  /* package */ OrderPaidController(final OrderPaidListener listener) {
    this.listener = listener;
  }

  /** 支払いを知らせる。 */
  @PostMapping("/{orderId}")
  /* package */ void paid(@PathVariable final String orderId) {
    listener.on(orderId);
  }
}
