package archfixture.violating.ordering.application;

import org.springframework.cache.annotation.Cacheable;

/** 違反：proxiedMethodsAreNotCalledFromSameClass（{@link OrderReprocessor} が継承して同じクラスから呼ぶメソッドを持つ）。 */
public class OrderReprocessorBase {

  /** 注文の状態を、キャッシュを通して読む。 */
  @Cacheable("orderStatus")
  public String cachedStatus(final String orderId) {
    return "PLACED:" + orderId;
  }
}
