package com.example.demo.testkit.idempotency;

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
    /** 出版が完了（archive）にならない。 */
    PUBLICATION_NOT_COMPLETED,
    /** 同時の 2 回のどちらかが例外で終わる。 */
    CONCURRENT_DELIVERY_FAILED,
    /** 同時の 2 回の後の業務の表が、1 回だけ届けたときと違う。 */
    CONCURRENT_RESULT_DIFFERS,
    /** 外部システムの呼び出しの組の集合が、1 回だけ届けたときと違う。 */
    EXTERNAL_CALLS_DIFFER,
    /** 同時の 2 回の検査が、決めた順に競合させられない（コミットの直前の門に届かない、2 回目が待ちも終わりもしない）。 */
    GATE_NOT_REACHED
  }

  @Override
  public String toString() {
    return listener + ": " + kind + ": " + detail;
  }
}
