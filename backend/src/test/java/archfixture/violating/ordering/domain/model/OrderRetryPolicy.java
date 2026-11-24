package archfixture.violating.ordering.domain.model;

import io.github.resilience4j.retry.Retry;

/** 違反：domainModelDependsOnlyOnAllowedTypes（外部ライブラリの型を戻り値にした interface）。 */
@FunctionalInterface
public interface OrderRetryPolicy {

  /** 再試行の設定を返す。 */
  Retry retryFor(OrderId orderId);
}
