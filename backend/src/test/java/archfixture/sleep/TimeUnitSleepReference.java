package archfixture.sleep;

import java.util.concurrent.TimeUnit;

/** {@code TimeUnit.MILLISECONDS::sleep} をメソッド参照で使う。 */
public final class TimeUnitSleepReference {

  /** {@code TimeUnit.MILLISECONDS::sleep} を {@link ThreadSleepReference.SleepAction} として返す。 */
  public ThreadSleepReference.SleepAction action() {
    return TimeUnit.MILLISECONDS::sleep;
  }
}
