package archfixture.violating;

import archfixture.violating.shared.failure.FailureReason;

/** 違反：failureReasonsAreModuleEnums（モジュールの外の理由）。 */
public enum RootFailure implements FailureReason {
  /** 要求を断る。 */
  REJECTED
}
