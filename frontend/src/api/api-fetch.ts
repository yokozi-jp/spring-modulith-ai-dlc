import type { ProblemDetail } from "@/api/generated/models";
import { csrfToken } from "@/lib/csrf";

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

async function readProblem(response: Response): Promise<ProblemDetail | undefined> {
  if (!(response.headers.get("Content-Type") ?? "").startsWith("application/problem+json")) {
    return undefined;
  }
  try {
    // 同一オリジンのバックエンドの形は契約テストが保証するため、Zod で検証しない（docs/frontend/api-client-orval.md）。
    // oxlint-disable-next-line typescript/no-unsafe-type-assertion
    return (await response.json()) as ProblemDetail;
  } catch {
    return undefined;
  }
}

/** CSRF の検査の対象外の method（RFC 9110 §9.2.1 の safe method のうち fetch で送れるもの）。 */
const csrfExemptMethods = new Set(["GET", "HEAD", "OPTIONS"]);

/** 更新系の要求にだけ、CSRF の Cookie の値を X-XSRF-TOKEN で付けた header を返す。 */
function requestHeaders(options: RequestInit): Headers {
  const headers = new Headers(options.headers);
  // 同じ origin の相対 URL だけを受ける前提で header を付ける。別の origin へ token を送らないよう、Orval の baseUrl を設定しない（docs/frontend/api-client-orval.md）。
  // method を省いた要求は Fetch の既定どおり GET として扱う。
  if (!csrfExemptMethods.has((options.method ?? "GET").toUpperCase())) {
    // token は認証とログアウトの成功時に作り直されるため、要求のたびに Cookie から読む。マスクはしない（csrf.spa() は header の値を素のまま照合する）。
    const token = csrfToken();
    if (token !== undefined) {
      headers.set("X-XSRF-TOKEN", token);
    }
  }
  return headers;
}

/**
 * Orval が生成した API 関数から呼ばれる mutator。
 * @public
 */
export async function apiFetch<TResponse>(url: string, options: RequestInit): Promise<TResponse> {
  const response = await fetch(url, { ...options, headers: requestHeaders(options) });
  if (!response.ok) {
    throw new ApiProblemError(response.status, await readProblem(response));
  }
  // 本文が空の 201（Location だけ）でも response.json() の SyntaxError にならないよう、テキストで読む。
  const text = await response.text();
  const data: unknown = text === "" ? undefined : JSON.parse(text);
  // 生成コードが期待する { data, status, headers } の型に合わせる。
  // oxlint-disable-next-line typescript/no-unsafe-type-assertion
  return { data, status: response.status, headers: response.headers } as TResponse;
}

/**
 * 生成される hook のエラー型（Orval が mutator のファイルから読む）。
 * @public
 */
export type ErrorType<_TError> = ApiProblemError;

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
  globalThis.location.assign(loginPath);
}

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
  return failureCount < 3;
}
