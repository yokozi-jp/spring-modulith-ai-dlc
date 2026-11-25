package com.example.demo.testkit.idempotency;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 冪等性の検査が見つけた違反。
 *
 * @param listener 違反したリスナー、または入力例が指すリスナー
 * @param kind 違反の種類
 * @param detail 何が悪いか。行の差、呼び出しの差、出版の状態など
 */
public record Violation(ListenerId listener, Kind kind, String detail) {

  /** 違反の種類。 */
  public enum Kind {
    /** リスナーに入力例がない。 */
    NO_EXAMPLE,
    /** 入力例が指すリスナーがない。 */
    ORPHAN_EXAMPLE,
    /** 1 回目の配信が業務の表の行を 1 つも変えない。 */
    FIRST_DELIVERY_CHANGED_NOTHING,
    /** 順の 2 回目の配信が業務の表の行を足す、消す、変える。 */
    SECOND_DELIVERY_CHANGED_ROWS,
    /**
     * 出版が待つ上限までに完了（archive）にならない。順の 2 回では {@code FAILED} も含む。同時の 2 回の {@code FAILED} は {@link
     * #CONCURRENT_DELIVERY_FAILED} にする。
     */
    PUBLICATION_NOT_COMPLETED,
    /** 同時の 2 回のどちらかが例外で終わり、出版が {@code FAILED} に残る。 */
    CONCURRENT_DELIVERY_FAILED,
    /** 同時の 2 回の後の業務の表が、1 回だけ届けたときと違う。 */
    CONCURRENT_RESULT_DIFFERS,
    /** 外部システムの呼び出しの組の集合が、1 回だけ届けたときと違う。 */
    EXTERNAL_CALLS_DIFFER,
    /** 同時の 2 回の 1 回目が、コミットの直前の門で止まらない。止めずに競合を作ったことにすると、検査が偽の合格を返す。 */
    FIRST_NOT_HELD,
    /** 同時の 2 回の 2 回目が、待つ上限までに 1 回目のロックを待たず、門にも届かず、失敗もしない。 */
    SECOND_NOT_CONTENDED
  }

  /** {@code violated} なら違反を返す。説明は違反のときだけ作る。 */
  /* package */ static Optional<Violation> when(
      final boolean violated,
      final ListenerId listener,
      final Kind kind,
      final Supplier<String> detail) {
    return violated ? Optional.of(new Violation(listener, kind, detail.get())) : Optional.empty();
  }

  @Override
  public String toString() {
    return listener + ": " + kind + ": " + detail;
  }
}
