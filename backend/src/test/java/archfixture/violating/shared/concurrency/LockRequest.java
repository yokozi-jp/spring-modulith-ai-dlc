package archfixture.violating.shared.concurrency;

/**
 * 違反：numericIdsDoNotLeaveModules（全モジュールが使う shared.concurrency の型が数値の ID を持つ）。
 *
 * @param rowId ロックする行の主キー
 */
public record LockRequest(long rowId) {}
