package archfixture.violating.ordering.presentation.web;

import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 違反：eventPublisherIsUsedOnlyByCommandHandlers（Controller が ApplicationEventMulticaster でイベントを配る）。
 */
@RestController
@RequestMapping("/api/order-multicasts")
class OrderEventMulticasterController {

  /** リスナーへイベントを直接配る。 */
  private final ApplicationEventMulticaster multicaster;

  /** イベントの配信先を受け取る。 */
  /* package */ OrderEventMulticasterController(final ApplicationEventMulticaster multicaster) {
    this.multicaster = multicaster;
  }

  /** 注文 ID をイベントとして配る。 */
  @PostMapping("/{orderId}")
  /* package */ void multicast(@PathVariable final String orderId) {
    multicaster.multicastEvent(new PayloadApplicationEvent<>(this, orderId));
  }
}
