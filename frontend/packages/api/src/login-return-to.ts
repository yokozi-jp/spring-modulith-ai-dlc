const returnToKey = "auth.returnTo";

// ログインへの遷移で文書が捨てられるため、memory には持てない。storage が使えないときは戻り先なしで `/` に着く。
/** ログインの後に着く画面。戻り先にしても `/` から動かないので保存しない。 */
const landingPaths = new Set(["/", "/tenants"]);

function resolveUrl(value: string): URL | undefined {
  try {
    return new URL(value, globalThis.location.origin);
  } catch {
    // `/\^@` のように host として解析できない値は、URL が例外を投げる。
    return undefined;
  }
}

/**
 * 戻り先として使える値を、同一オリジンの path、query、hash に作り直して返す。使えない値は undefined にする。
 * ブラウザの URL の解析で解決し、`\`、tab、改行の扱いを遷移の先と食い違わせない。
 */
export function parseReturnTo(value: unknown): string | undefined {
  if (typeof value !== "string" || !value.startsWith("/")) {
    return undefined;
  }
  const url = resolveUrl(value);
  if (url === undefined) {
    return undefined;
  }
  const resolved = `${url.pathname}${url.search}${url.hash}`;
  // `/.//evil.example` は pathname が `//evil.example` に正規化される。遷移の先で別の origin と読まれないよう拒む。
  if (url.origin !== globalThis.location.origin || resolved.startsWith("//")) {
    return undefined;
  }
  return resolved;
}

function removeStored(): void {
  try {
    globalThis.sessionStorage.removeItem(returnToKey);
  } catch {
    // storage が使えないときは、消す値もない。
  }
}

/**
 * ログインへ遷移する前に、いまの path、query、hash を戻り先として 1 つだけ保存する。
 * ログインの後に着く画面（`/`、`/tenants`）は保存せず、前の値も消す。
 */
export function saveReturnTo(): void {
  const { pathname, search, hash } = globalThis.location;
  if (landingPaths.has(pathname)) {
    removeStored();
    return;
  }
  try {
    globalThis.sessionStorage.setItem(returnToKey, `${pathname}${search}${hash}`);
  } catch {
    // 戻り先なしで `/` に着く。
  }
}

/** 保存した戻り先を 1 回だけ読み、消す。保存がないか、使えない値のときは undefined にする。 */
export function takeReturnTo(): string | undefined {
  let stored: string | null = "";
  try {
    stored = globalThis.sessionStorage.getItem(returnToKey);
  } catch {
    // storage が使えないときは、読む値がない。
  }
  removeStored();
  return parseReturnTo(stored);
}

/** 保存した戻り先を、使わずに消す。 */
export const clearReturnTo = removeStored;
