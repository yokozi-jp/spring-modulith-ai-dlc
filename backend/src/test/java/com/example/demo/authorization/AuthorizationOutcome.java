package com.example.demo.authorization;

/** 認可の表の 1 つの組に期待する結果（ADR-129、docs/web-api/status-codes.md）。 */
enum AuthorizationOutcome {
  /** 許す。2xx を返し、本文は見えるものを持ち、見えないものを持たない（{@link AuthorizationExpectations.Shown}）。 */
  ALLOWED,
  /**
   * 見えない。対象が存在しないときと同じ status と本文を返す。id を持つ操作では、存在しない id で叩いたときと同じになる。
   *
   * <p>存在しないときの応答は、spec が 404 を書く操作では 404、書かない操作（一覧）では空の 200 である。
   */
  HIDDEN,
  /** 見えるが許さない、またはエンドポイントを呼ぶ権限がない。403 を返す。 */
  FORBIDDEN,
  /** 本文で指定した対象が見えず、業務の条件を満たさない。422 を返す。 */
  UNPROCESSABLE
}
