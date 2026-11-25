package com.example.demo;

/** 認可の表の 1 つの組に期待する結果（ADR-129、docs/web-api/status-codes.md）。 */
enum AuthorizationOutcome {
  /** 許す。2xx を返す。 */
  ALLOWED,
  /** 見えない。対象が存在しないときと同じ status と本文を返す。id を持つ操作では、存在しない id で叩いたときと同じになる。 */
  HIDDEN,
  /** 見えるが許さない、またはエンドポイントを呼ぶ権限がない。403 を返す。 */
  FORBIDDEN,
  /** 本文で指定した対象が見えず、業務の条件を満たさない。422 を返す。 */
  UNPROCESSABLE
}
