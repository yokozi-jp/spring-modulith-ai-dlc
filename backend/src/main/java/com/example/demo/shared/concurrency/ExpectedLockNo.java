package com.example.demo.shared.concurrency;

/**
 * 画面が表示した集約ルートの版（{@code lock_no}）。更新と削除の Command が持つ。
 *
 * <p>Request だけが作る。{@code lock_no} は INSERT が 1 で書くため、1 未満は受け付けない。HTTP の境界は {@code @Min(1)} で先に 400
 * にするので、ここは最後の砦である。
 *
 * @param value 画面が表示した版
 */
public record ExpectedLockNo(long value) {

  /** 版の最小値。INSERT が {@code lock_no = 1} で書く。 */
  private static final long FIRST_LOCK_NO = 1L;

  /**
   * 版が 1 以上であることを確かめる。
   *
   * @throws IllegalArgumentException {@code value} が 1 未満の場合
   */
  public ExpectedLockNo {
    if (value < FIRST_LOCK_NO) {
      throw new IllegalArgumentException(
          "expected lock number must be 1 or greater: value=" + value);
    }
  }
}
