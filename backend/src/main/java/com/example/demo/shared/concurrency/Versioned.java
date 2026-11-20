package com.example.demo.shared.concurrency;

/**
 * 読み込んだときの版（{@code lock_no}）を持つ集約ルートの印。
 *
 * <p>{@code TableWriter.update} は、集約ルートをこの型で受け取って版を比べる。版の数値を渡す入口はなく、集約ルートのクラスを `AggregateTable`
 * の型引数に指定すれば、テーブルから読み直した版を期待する版に渡す誤りが書けない（ADR-054）。
 */
// 集約ルートの印であり、本番のコードはラムダで実装する関数型として使わないため、@FunctionalInterface にしない。テストは版だけを持つ値をラムダで作ってよい。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface Versioned {

  /** 読み込んだときの版を返す。新しく作った集約では使わない。 */
  long lockNo();
}
