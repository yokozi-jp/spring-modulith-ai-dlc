package archfixture.violating.ordering.application;

import io.github.resilience4j.retry.Retry;

/** 違反：applicationDependsOnlyOnAllowedTypes（外部ライブラリの型を戻り値にした interface）。 */
@FunctionalInterface
public interface OrderRetryRunner {

  /** 再試行の設定を返す。 */
  Retry retry();
}
