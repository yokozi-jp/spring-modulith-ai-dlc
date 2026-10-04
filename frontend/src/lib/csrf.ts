// csrf.spa() はフォームの _csrf を XorCsrfTokenRequestAttributeHandler で検証するので、Cookie の値をマスクして送る。
// header（X-XSRF-TOKEN）で送るならマスクは要らない。
export function maskedCsrfToken() {
  const token =
    document.cookie
      .split("; ")
      .find((cookie) => cookie.startsWith("XSRF-TOKEN="))
      ?.slice("XSRF-TOKEN=".length) ?? "";
  const bytes = new TextEncoder().encode(token);
  const random = crypto.getRandomValues(new Uint8Array(bytes.length));
  // oxlint-disable-next-line eslint/no-bitwise -- Spring Security のマスクは XOR で定義されている。
  const xored = bytes.map((byte, index) => byte ^ (random[index] ?? 0));
  return btoa(String.fromCodePoint(...random, ...xored))
    .replaceAll("+", "-")
    .replaceAll("/", "_");
}
