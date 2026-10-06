package com.example.demo.shared.concurrency;

/** 画面が表示した版を持つ Command の印。集約の更新と削除の Command が実装する。 */
// Command の印であり、ラムダで実装する関数型ではないため、@FunctionalInterface にしない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface VersionedCommand {

  /** 画面が表示した集約ルートの版を返す。 */
  ExpectedLockNo expectedLockNo();
}
