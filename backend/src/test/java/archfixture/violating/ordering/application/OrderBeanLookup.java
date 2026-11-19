package archfixture.violating.ordering.application;

import org.springframework.context.ApplicationContext;

/**
 * 違反：eventPublisherIsUsedOnlyByCommandHandlers（application の CommandHandler 以外が ApplicationContext
 * に依存する）。
 */
public class OrderBeanLookup {

  /** Bean とイベントの発行口を持つコンテキスト。 */
  private final ApplicationContext context;

  /** コンテキストを受け取る。 */
  public OrderBeanLookup(final ApplicationContext context) {
    this.context = context;
  }

  /** コンテキストから注文 ID をイベントとして発行する。 */
  public void publish(final String orderId) {
    context.publishEvent(orderId);
  }
}
