package archfixture.sleep;

/** 固定時間の待機ではない、同名の {@code sleep} メソッドを呼ぶ。規則が誤検出しない確認に使う。 */
public final class UnrelatedSleep {

  /** 呼び出し側。 */
  public long call() {
    return sleep(1L) + ((java.util.function.LongUnaryOperator) this::sleep).applyAsLong(1L);
  }

  private long sleep(final long millis) {
    return millis;
  }
}
