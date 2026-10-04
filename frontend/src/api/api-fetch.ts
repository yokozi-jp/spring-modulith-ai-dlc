import type { ProblemDetail } from "@/api/generated/models";

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

/**
 * Orval が生成した API 関数から呼ばれる mutator。
 * @public
 */
export async function apiFetch<TResponse>(url: string, options: RequestInit): Promise<TResponse> {
  const response = await fetch(url, options);
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

/** 4xx の ApiProblemError は再試行しても結果が変わらないため、再試行しない。 */
export function retryUnlessClientError(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiProblemError && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < 3;
}
