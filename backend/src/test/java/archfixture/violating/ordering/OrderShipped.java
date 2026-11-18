package archfixture.violating.ordering;

/** 違反：numericIdsDoNotLeaveModules（モジュール間のイベントに数値の注文 ID を載せる）。 */
public record OrderShipped(long orderId) {}
