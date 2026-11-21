package archfixture.conforming.shared.concurrency;

/** 読み込んだときの版を持つ集約ルートの印。フィクスチャなので、規則が見る名前だけを持つ。 */
// 集約ルートの印であり、ラムダで実装する関数型ではないため、@FunctionalInterface にしない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface Versioned {

  /** 読み込んだときの版を返す。 */
  long lockNo();
}
