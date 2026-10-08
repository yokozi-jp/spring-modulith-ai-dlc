package archfixture.violating.ordering.domain.service;

import archfixture.violating.ordering.domain.model.OrderId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** 違反：domainServicesDependOnlyOnDomainAndJava（Domain Service がイベントを発行する）。 */
@Service
public class DiscountPolicy {

  /** イベントを発行する。 */
  private final ApplicationEventPublisher publisher;

  /** イベントの発行先を受け取る。 */
  public DiscountPolicy(final ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  /** 割引を適用したことを知らせる。 */
  public void announce(final OrderId orderId) {
    publisher.publishEvent(orderId);
  }
}
