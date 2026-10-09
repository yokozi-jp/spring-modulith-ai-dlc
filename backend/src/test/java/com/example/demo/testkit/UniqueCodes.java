package com.example.demo.testkit;

import java.util.UUID;

/**
 * 一意制約のある業務コード（商品コード、客先注文番号）を、テストごとに重ならない値で作る補助。
 *
 * <p>コミットするテストや非同期の Listener が残した行と、固定の値が一意制約で衝突しないようにする。
 */
public final class UniqueCodes {

  /** UUID の 16 進の文字列から使う桁数。 */
  private static final int HEX_DIGITS = 12;

  private UniqueCodes() {}

  /** {@code prefix + "-" + UUID の先頭 12 桁の 16 進} を返す（{@code P-1a2b3c4d5e6f}）。 */
  public static String next(final String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, HEX_DIGITS);
  }
}
