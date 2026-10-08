package archfixture.violating.ordering;

import archfixture.violating.ordering.domain.model.OrderId;

/** 違反：moduleApiDoesNotExposeInternalTypes（ルートの record が domain.model の型を持つ）。 */
public record OrderPlacedWithModel(OrderId orderId) {}
