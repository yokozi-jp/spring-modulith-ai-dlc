package archfixture.violating.ordering.application;

import org.springframework.resilience.annotation.Retryable;

/** 違反：retryableIsNotUsedOnExternalSystemPath（Listener の {@code on} に {@code @Retryable} を付ける）。 */
// 規約の名前 on に合わせるため、短いメソッド名を許す。
@SuppressWarnings("PMD.ShortMethodName")
public class OrderRefundedListener {

  /** 返金を受け取る。 */
  @Retryable
  public void on(final String orderId) {
    // 受け取るだけ。
  }
}
