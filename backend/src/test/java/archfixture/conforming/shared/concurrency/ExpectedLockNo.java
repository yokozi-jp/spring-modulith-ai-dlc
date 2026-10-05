package archfixture.conforming.shared.concurrency;

/**
 * 画面が表示した集約ルートの版。フィクスチャなので検証を省く。
 *
 * @param value 画面が表示した版
 */
public record ExpectedLockNo(long value) {}
