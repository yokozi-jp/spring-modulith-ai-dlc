package archfixture.conforming.ordering.domain.model;

import archfixture.conforming.shared.failure.FailureReason;

/** 注文の業務規則の違反の理由。 */
public enum OrderFailure implements FailureReason {
  /** 受付でない注文を取り消そうとした。 */
  NOT_PLACED {
    // 定数の本体のクラスも failureReasonsAreModuleEnums を通ることを確かめる。
  },
  /** 未出荷の注文の数が上限に達した。 */
  UNSHIPPED_LIMIT_REACHED
}
