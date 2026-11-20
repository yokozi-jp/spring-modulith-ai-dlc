package archfixture.violating.shared.events;

import org.springframework.context.ApplicationEventPublisher;

/**
 * 違反：eventPublisherIsUsedOnlyByCommandHandlers（shared に置いた、発行を包むクラス。どの層からも依存できるため、CommandHandler
 * 以外が発行する入口になる）。
 */
public class OrderEventForwarder {

  /** イベントを発行する。 */
  private final ApplicationEventPublisher publisher;

  /** イベントの発行先を受け取る。 */
  public OrderEventForwarder(final ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  /** 注文 ID をイベントとして発行する。 */
  public void forward(final String orderId) {
    publisher.publishEvent(orderId);
  }
}
