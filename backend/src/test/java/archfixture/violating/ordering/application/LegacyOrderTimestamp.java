package archfixture.violating.ordering.application;

import java.util.Date;

/** 違反：legacyDateTimeTypesAreNotUsed（絶対時刻を {@code java.util.Date} で表す）。 */
// ArchUnit が java.util.Date への依存を検出することを確かめる違反フィクスチャのため、Error Prone と PMD の検査を外す。
@SuppressWarnings({"JavaUtilDate", "PMD.ReplaceJavaUtilDate"})
public class LegacyOrderTimestamp {

  /** 注文の受付時刻を {@code java.util.Date} で返す。 */
  public Date placedAt(final long epochMilli) {
    return new Date(epochMilli);
  }
}
