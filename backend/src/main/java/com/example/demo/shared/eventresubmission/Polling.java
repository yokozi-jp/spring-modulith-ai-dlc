package com.example.demo.shared.eventresubmission;

import java.time.Duration;

/** ロックの解放とリスナーの完了を、短い間隔で確かめるための待ち。 */
final class Polling {

  /** ロックと完了を確かめる間隔。 */
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private Polling() {}

  /**
   * {@code limit} の間に確かめる回数。int を超える上限は切り捨てず {@link ArithmeticException} で失敗する。
   *
   * <p>回数を int にするのは、PIT が int のループの増分の変異を無限ループとして除くためである。long の増分は除かれず、待ちの上限まで止まる。
   */
  /* package */ static int pollsWithin(final Duration limit) {
    return Math.toIntExact(limit.dividedBy(POLL_INTERVAL));
  }

  /** 次に確かめるまで待つ。割り込まれたら、割り込みを戻して失敗する。 */
  // ロックの解放とリスナーの完了を、短い間隔で確かめるため待つ。
  @SuppressWarnings("PMD.DoNotUseThreads")
  /* package */ static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while resubmitting once", e);
    }
  }
}
