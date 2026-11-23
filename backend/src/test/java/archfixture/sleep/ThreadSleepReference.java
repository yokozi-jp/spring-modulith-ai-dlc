package archfixture.sleep;

/** {@code Thread::sleep} をメソッド参照で使う。 */
// 規則が検出する対象として Thread::sleep を参照する。
@SuppressWarnings("PMD.DoNotUseThreads")
public final class ThreadSleepReference {

  /** {@code Thread::sleep} を {@link SleepAction} として返す。 */
  public SleepAction action() {
    return Thread::sleep;
  }

  /** {@code sleep} の呼び出しを表す関数。 */
  @FunctionalInterface
  public interface SleepAction {

    /** 眠る。 */
    void run(long millis) throws InterruptedException;
  }
}
