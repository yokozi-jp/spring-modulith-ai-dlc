package com.example.demo.shared.concurrency;

/**
 * 読み込んだときの版（{@code lock_no}）を持つ集約ルートの印。
 *
 * <p>集約ルートはこの型を実装する。{@code TableWriter} の {@code insert} と {@code update}
 * は、集約ルートだけを受け取るために、この型を要る。INSERT は版を読まないので、新しい集約は {@link #lockNo}
 * に意味のある値を返さなくてよい。アーキテクチャテスト（H6）も、この実装で集約ルートを見分ける。
 *
 * <p>{@code TableWriter.update} は、集約ルートをこの型で受け取って版を比べる。版の数値を渡す入口はなく、集約ルートのクラスを {@code
 * AggregateTable}
 * の型引数に指定すれば、テーブルから読み直した版を期待する版に渡す誤りが書けない。読み直した版で集約ルートを作り直して渡す誤りは型では止まらず、レビューで見る（ADR-054）。
 */
// 集約ルートの印であり、ラムダで実装する関数型ではないため、@FunctionalInterface にしない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface Versioned {

  /** 版の最小値。INSERT が {@code lock_no = 1} で書く。 */
  long FIRST_LOCK_NO = 1L;

  /** 読み込んだときの版を返す。新しく作った集約では使わない。 */
  long lockNo();
}
