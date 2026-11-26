import { ApiProblemError } from "./api-error";
import { saveReturnTo } from "./login-return-to";

/** バックエンドの OAuth2 のログインの入口。 */
export const loginPath = "/oauth2/authorization/web";
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

/** 直前に遷移していなければ、戻り先を保存してログインへ遷移する。 */
function redirectToLogin(): void {
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

const sessionExpiredPath = "/session-expired";

/**
 * QueryCache と MutationCache に渡す、401 の遷移の onSuccess と onError を作る。状態は QueryClient ごとに持つ。
 * 同じ文書で API が成功した後の 401 は、有効期限切れの画面へ移る。最初の 401 は、直前に遷移していなければログインへ移る。
 */
export function createUnauthorizedRedirect(): {
  onSuccess: () => void;
  onError: (error: unknown) => void;
} {
  let succeeded = false;
  return {
    onSuccess: () => {
      succeeded = true;
    },
    onError: (error) => {
      if (!(error instanceof ApiProblemError) || error.status !== 401) {
        return;
      }
      if (!succeeded) {
        redirectToLogin();
        return;
      }
      // 抑止を掛けない。同じ文書で 401 が続くと assign を重ねて呼ぶが、最後の遷移が残るだけで害はない。
      saveReturnTo();
      globalThis.location.assign(sessionExpiredPath);
    },
  };
}
