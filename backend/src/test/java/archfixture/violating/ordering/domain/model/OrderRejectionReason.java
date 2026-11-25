package archfixture.violating.ordering.domain.model;

import archfixture.violating.shared.failure.FailureReason;

/**
 * 違反：failureReasonsAreModuleEnums（enum でない理由）。
 *
 * @param name 理由の名前
 */
public record OrderRejectionReason(String name) implements FailureReason {}
