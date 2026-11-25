import type { ProblemDetail } from "./generated/models";
import { saveReturnTo } from "./login-return-to";

/** API が 2xx 以外を返したことを表す。problem は application/problem+json の本文。 */
export class ApiProblemError extends Error {
  public readonly status: number;
  public readonly problem: ProblemDetail | undefined;
  public constructor(status: number, problem: ProblemDetail | undefined) {
    // detail は内部情報を含みうるため message に入れない。
    super(`API request failed with status ${status}`);
    this.name = "ApiProblemError";
    this.status = status;
    this.problem = problem;
  }
}

/** テナントを選ぶ前に業務の API を呼んだ 403 の problem type（契約と合っているかは api-fetch.test.ts が確かめる）。 */
const tenantNotSelectedType = "/problems/tenant-not-selected";

/**
 * テナントを選ぶ前に業務の API を呼んだ 403 なら true を返す。権限不足の 403（about:blank）と CSRF の 403 は false にする。
 * @public
 */
export function isTenantNotSelected(error: unknown): error is ApiProblemError {
  return (
    error instanceof ApiProblemError &&
    error.status === 403 &&
    error.problem?.type === tenantNotSelectedType
  );
}

const loginPath = "/oauth2/authorization/web";
const redirectGuardMs = 30_000;
const redirectedAtKey = "api.loginRedirectedAt";

// ponytail: memory の値は再読み込みで消える。storage が使えない browser で遷移を繰り返すようになったら、URL の query で回数を渡す。
let redirectedAtFallback = Number.NaN;

function readRedirectedAt(): number {
  try {
    const stored = globalThis.sessionStorage.getItem(redirectedAtKey);
    // setItem だけが失敗した場合に備え、storage に値がなければ memory を見る。
    return stored === null ? redirectedAtFallback : Number(stored);
  } catch {
    return redirectedAtFallback;
  }
}

function writeRedirectedAt(redirectedAt: number): void {
  try {
    globalThis.sessionStorage.setItem(redirectedAtKey, String(redirectedAt));
  } catch {
    redirectedAtFallback = redirectedAt;
  }
}

/** query と mutation の失敗が 401 なら、直前に遷移していなければログインへ遷移する。 */
export function redirectToLoginOnUnauthorized(error: unknown): void {
  if (!(error instanceof ApiProblemError) || error.status !== 401) {
    return;
  }
  const now = Date.now();
  const redirectedAt = readRedirectedAt();
  // 数値でない保存値（NaN や Infinity）は遷移していないとして扱う。
  if (Number.isFinite(redirectedAt) && now - redirectedAt < redirectGuardMs) {
    return;
  }
  writeRedirectedAt(now);
  saveReturnTo();
  globalThis.location.assign(loginPath);
}

/** 同じ Idempotency-Key の要求をサーバーが処理している 409 の problem type（ADR-126）。 */
const idempotencyKeyInProgressType = "/problems/idempotency-key-in-progress";

/**
 * 同じ Idempotency-Key の要求の処理中の 409 なら true を返す。版の競合などほかの 409 は false にする。
 * @public
 */
export function isIdempotencyKeyInProgress(error: unknown): error is ApiProblemError {
  return (
    error instanceof ApiProblemError &&
    error.status === 409 &&
    error.problem?.type === idempotencyKeyInProgressType
  );
}

/**
 * 同じ Idempotency-Key の要求の処理中でない 409（版の競合など）なら true を返す。
 * @public
 */
export function isStateConflict(error: unknown): error is ApiProblemError {
  return (
    error instanceof ApiProblemError && error.status === 409 && !isIdempotencyKeyInProgress(error)
  );
}

/** 同じ要求を再試行する回数の上限。 */
const maxRetries = 3;

/** 再試行してよい 4xx（RFC 9110 §15.5.9 の 408、RFC 6585 §4 の 429）。 */
const retryableClientErrors = new Set([408, 429]);

/** 408 と 429 以外の 4xx の ApiProblemError は再試行しても結果が変わらないため、再試行しない。 */
export function retryUnlessClientError(failureCount: number, error: unknown): boolean {
  if (
    error instanceof ApiProblemError &&
    error.status >= 400 &&
    error.status < 500 &&
    !retryableClientErrors.has(error.status)
  ) {
    return false;
  }
  // ponytail: 429 の Retry-After を見ず、TanStack Query の既定の間隔で再試行する。バックエンドに rate limit を入れるときに Retry-After を見る。
  return failureCount < maxRetries;
}

/**
 * mutation を同じ変数（同じ Idempotency-Key）で再試行するかを決める（ADR-126）。
 * ネットワークの失敗（fetch の TypeError）、5xx、408、429、処理中の 409 だけを、3 回まで再試行する。
 * ほかの 4xx と、版の競合などほかの 409 は再試行しない。
 */
export function retryIdempotentMutation(failureCount: number, error: unknown): boolean {
  if (failureCount >= maxRetries) {
    return false;
  }
  if (!(error instanceof ApiProblemError)) {
    return error instanceof TypeError;
  }
  // ponytail: Retry-After を読まない。既定の backoff の最初の待ち（1 秒）が、処理中の 409 の Retry-After（1 秒）以上である。
  // サーバーがそれより長い Retry-After を返すようになったら、ApiProblemError に値を持たせて retryDelay で待つ。
  return (
    error.status >= 500 ||
    retryableClientErrors.has(error.status) ||
    isIdempotencyKeyInProgress(error)
  );
}
