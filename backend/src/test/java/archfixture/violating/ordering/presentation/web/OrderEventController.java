package archfixture.violating.ordering.presentation.web;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：eventPublisherIsUsedOnlyByCommandHandlers（Controller がイベントを発行する）。 */
@RestController
@RequestMapping("/api/order-events")
class OrderEventController {

  /** イベントを発行する。 */
  private final ApplicationEventPublisher publisher;

  /** イベントの発行先を受け取る。 */
  /* package */ OrderEventController(final ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  /** 注文 ID をイベントとして発行する。 */
  @PostMapping("/{orderId}")
  /* package */ void publish(@PathVariable final String orderId) {
    publisher.publishEvent(orderId);
  }
}
