package archfixture.violating.shared.persistence;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 違反：scheduledMethodsAreNoArgVoidAndNotPrivateOrFinal（@Scheduled のメソッドが値を返す、引数を取る、private、final
 * である。繰り返した @Scheduled のメソッドが値を返す）。
 */
public class StaleLockSweeper {

  /** 掃除した行の数。 */
  private final AtomicLong swept = new AtomicLong();

  /** 古いロックを掃除し、掃除した行の数を返す。 */
  @Scheduled(fixedDelay = 60_000)
  public long sweep() {
    return swept.incrementAndGet();
  }

  /** 指定した経過時間より古いロックを掃除する。 */
  @Scheduled(fixedDelay = 60_000)
  public void sweepOlderThan(final Duration age) {
    swept.addAndGet(age.toSeconds());
  }

  // 違反の形を作るため、呼ばない private メソッドに @Scheduled を付ける。
  @SuppressWarnings({"UnusedMethod", "unused"})
  @Scheduled(fixedDelay = 60_000)
  private void sweepPrivately() {
    swept.incrementAndGet();
  }

  /** 古いロックを掃除する。 */
  @Scheduled(fixedDelay = 60_000)
  public final void sweepFinally() {
    swept.incrementAndGet();
  }

  /** 二つの間隔で古いロックを掃除し、掃除した行の数を返す。 */
  @Scheduled(fixedDelay = 60_000)
  @Scheduled(initialDelay = 1_000, fixedDelay = 120_000)
  public long sweepTwice() {
    return swept.incrementAndGet();
  }
}
