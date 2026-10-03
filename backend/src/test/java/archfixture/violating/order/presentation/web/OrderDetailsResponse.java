package archfixture.violating.order.presentation.web;

import archfixture.violating.order.domain.model.OrderId;

/** 違反：presentationDoesNotDependOnDomain（Response が domain.model の型を持つ）。 */
public record OrderDetailsResponse(OrderId orderId) {}
