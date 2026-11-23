const returnToKey = "auth.returnTo";

// ponytail: memory の値は再読み込みで消える。storage が使えない browser では、ログインの往復で戻り先が失われ `/` に着く。
let returnToFallback = "";

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

/** ログインへ遷移する前に、いまの path、query、hash を戻り先として 1 つだけ保存する。 */
export function saveReturnTo(): void {
  const { pathname, search, hash } = globalThis.location;
  const returnTo = `${pathname}${search}${hash}`;
  returnToFallback = returnTo;
  try {
    globalThis.sessionStorage.setItem(returnToKey, returnTo);
  } catch {
    // storage が使えないときは memory の値が残る。
  }
}

/** 保存した戻り先を 1 回だけ読み、消す。保存がないか、使えない値のときは undefined にする。 */
export function takeReturnTo(): string | undefined {
  let stored = returnToFallback;
  try {
    stored = globalThis.sessionStorage.getItem(returnToKey) ?? stored;
    globalThis.sessionStorage.removeItem(returnToKey);
  } catch {
    // storage が使えないときは memory の値を使う。
  }
  returnToFallback = "";
  return parseReturnTo(stored);
}

/** 保存した戻り先を、使わずに消す。 */
export function clearReturnTo(): void {
  takeReturnTo();
}
