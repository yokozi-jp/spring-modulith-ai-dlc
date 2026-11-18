package archfixture.violating.ordering.application;

/** 違反：numericIdsDoNotLeaveModules（Application の Command に数値の注文 ID を持たせる）。 */
public record RefundOrderCommand(Long orderId) {}
