import { ApiProblemError } from "./api-error";
import { csrfToken } from "./csrf";
import type { ProblemDetail } from "./generated/models";

async function readProblem(response: Response): Promise<ProblemDetail | undefined> {
  if (!(response.headers.get("Content-Type") ?? "").startsWith("application/problem+json")) {
    return undefined;
  }
  try {
    // oxlint-disable-next-line typescript/no-unsafe-type-assertion -- 同一オリジンのバックエンドの形は契約テストが保証するため、Zod で検証しない（docs/frontend/api-client-orval.md）。
    return (await response.json()) as ProblemDetail;
  } catch {
    return undefined;
  }
}

/** CSRF の検査の対象外の method（RFC 9110 §9.2.1 の safe method のうち fetch で送れるもの）。 */
const csrfExemptMethods = new Set(["GET", "HEAD", "OPTIONS"]);

/** CSRF token の検証に失敗した 403 の problem type。再送の対象はこの type の 403 だけにする（契約と合っているかは api-fetch.test.ts が確かめる）。 */
const csrfTokenInvalidType = "/problems/csrf-token-invalid";

/** 更新系の要求にだけ、CSRF の Cookie の値を X-XSRF-TOKEN で付けた header を返す。 */
function requestHeaders(url: string, options: RequestInit): Headers {
  const headers = new Headers(options.headers);
  // 別の origin へ token を送らないよう、送信先を location の上で解決した origin が同じときだけ付ける。
  // method を省いた要求は Fetch の既定どおり GET として扱う。
  if (
    !csrfExemptMethods.has((options.method ?? "GET").toUpperCase()) &&
    new URL(url, globalThis.location.href).origin === globalThis.location.origin
  ) {
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
  // 送るたびに Cookie から token を読み直す。
  async function send() {
    const response = await fetch(url, { ...options, headers: requestHeaders(url, options) });
    return { response, problem: response.ok ? undefined : await readProblem(response) };
  }
  let { response, problem } = await send();
  // CSRF の 403 だけ、Cookie を読み直して 1 回だけ再送する。権限不足の 403（about:blank）と 2 回目の失敗は再送しない。
  // 再送してよい根拠：この type を返すのは CSRF filter の失敗だけで、1 通目は controller に届いていない。非冪等な POST でも二重には実行されない。
  // 再送が効くのは、Cookie の欠落（403 が新しい token の Cookie を置く）と、ログインへの遷移などで token が入れ替わった競合だけである。
  // Cookie があって header と食い違うときは 403 が Cookie を作り直さず、同じ組で再送して同じ 403 になる。この不一致そのものは救えない。
  // ponytail: 並行する複数の更新では、header を組んでから送るまでに別の 403 が Cookie を書き換えると、header と Cookie がずれる。
  // 再送は 1 回で打ち切るため、この競合に当たった要求は 403 のままになる。同時に複数の更新を発行する画面を作るときに再確認する。
  // ponytail: 本文は Orval が作る文字列を前提に同じ options で再送する。ストリームの本文を送るようになったら、再送の前に本文を作り直す。
  if (response.status === 403 && problem?.type === csrfTokenInvalidType) {
    ({ response, problem } = await send());
  }
  if (!response.ok) {
    throw new ApiProblemError(response.status, problem);
  }
  // 本文が空の 201（Location だけ）でも response.json() の SyntaxError にならないよう、テキストで読む。
  const text = await response.text();
  const data: unknown = text === "" ? undefined : JSON.parse(text);
  // oxlint-disable-next-line typescript/no-unsafe-type-assertion -- 生成コードが期待する { data, status, headers } の型に合わせる。
  return { data, status: response.status, headers: response.headers } as TResponse;
}

/**
 * 生成される hook のエラー型（Orval が mutator のファイルから読む）。
 * @public
 */
export type ErrorType<_TError> = ApiProblemError;
