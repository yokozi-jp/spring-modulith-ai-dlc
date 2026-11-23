package archfixture.sleep;

import java.util.concurrent.TimeUnit;

/** {@code TimeUnit.sleep} を直接呼ぶ。 */
public final class TimeUnitSleepCall {

  /** {@code TimeUnit.sleep} を呼ぶ。 */
  public void call() throws InterruptedException {
    TimeUnit.MILLISECONDS.sleep(1L);
  }
}
