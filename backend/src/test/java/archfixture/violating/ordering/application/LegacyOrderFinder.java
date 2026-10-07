package archfixture.violating.ordering.application;

import java.util.NoSuchElementException;
import java.util.Optional;

/** 違反：noSuchElementExceptionIsNotThrown（見つからないことを JDK の NoSuchElementException で表す）。 */
// 引数なしの orElseThrow() を ArchUnit が検出することを確かめる違反フィクスチャのため、テストの PMD の規則を外す。
@SuppressWarnings("PMD.RequireDiagnosticOptionalFailure")
final class LegacyOrderFinder {

  private LegacyOrderFinder() {}

  /** 見つからない注文を NoSuchElementException で投げる。 */
  /* package */ static String notFound(final Optional<String> order) {
    if (order.isEmpty()) {
      throw new NoSuchElementException("order not found");
    }
    return order.get();
  }

  /** 引数なしの orElseThrow() で、空の Optional から NoSuchElementException を投げる。 */
  /* package */ static String firstOrFail() {
    return Optional.<String>empty().orElseThrow();
  }
}
