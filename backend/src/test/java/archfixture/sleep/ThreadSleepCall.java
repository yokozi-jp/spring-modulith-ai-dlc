package archfixture.sleep;

/** {@code Thread.sleep} を直接呼ぶ。 */
// 規則が検出する対象として Thread.sleep を呼ぶ。
@SuppressWarnings("PMD.DoNotUseThreads")
public final class ThreadSleepCall {

  /** {@code Thread.sleep} を呼ぶ。 */
  public void call() throws InterruptedException {
    Thread.sleep(1L);
  }
}
