package archfixture.violating.ordering.domain.model;

/** 違反：numericIdsDoNotLeaveModules（Domain に数値の ID を持たせる）。 */
public record Shipment(Integer id) {}
