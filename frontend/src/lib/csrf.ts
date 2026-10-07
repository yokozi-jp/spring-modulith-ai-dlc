/** CSRF の Cookie の名前。backend の SecurityConfig と ZAP の script と同じ値にする（ADR-066）。 */
const csrfCookieName = "__Host-XSRF-TOKEN";

/**
 * CSRF の Cookie の値。Cookie がないか値が空なら undefined を返す（ADR-066）。
 * browser だけで呼ぶ前提で document を読む。
 * Node のテストでは document を stub する。
 */
export function csrfToken(): string | undefined {
  const value = document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith(`${csrfCookieName}=`))
    ?.slice(csrfCookieName.length + 1);
  return value === "" ? undefined : value;
}

// csrf.spa() はフォームの _csrf を XorCsrfTokenRequestAttributeHandler で検証するので、Cookie の値をマスクして送る。
// header（X-XSRF-TOKEN）で送るならマスクは要らない。
export function maskedCsrfToken() {
  const bytes = new TextEncoder().encode(csrfToken() ?? "");
  const random = crypto.getRandomValues(new Uint8Array(bytes.length));
  // oxlint-disable-next-line eslint/no-bitwise -- Spring Security のマスクは XOR で定義されている。
  const xored = bytes.map((byte, index) => byte ^ (random[index] ?? 0));
  return btoa(String.fromCodePoint(...random, ...xored))
    .replaceAll("+", "-")
    .replaceAll("/", "_");
}
