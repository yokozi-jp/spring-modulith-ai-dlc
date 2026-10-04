import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { maskedCsrfToken } from "./csrf";

const csrfToken = "0b1e5c1a-4f0e-4c55-9d8e-2f1a3b4c5d6e";

// XorCsrfTokenRequestAttributeHandler と同じく、base64url を decode して前半と後半を XOR する。
function unmask(value: string) {
  const decoded = Uint8Array.from(
    atob(value.replaceAll("-", "+").replaceAll("_", "/")),
    (char) => char.codePointAt(0) ?? 0,
  );
  const half = decoded.length / 2;
  const random = decoded.slice(0, half);
  // oxlint-disable-next-line eslint/no-bitwise -- Spring Security のマスクは XOR で定義されている。
  const token = decoded.slice(half).map((byte, index) => byte ^ (random[index] ?? 0));
  return new TextDecoder().decode(token);
}

describe("maskedCsrfToken", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("masks the XSRF-TOKEN cookie so that Spring Security can unmask it", () => {
    vi.stubGlobal("document", { cookie: `other=1; XSRF-TOKEN=${csrfToken}` });

    const masked = maskedCsrfToken();

    expect(masked).not.toBe(csrfToken);
    expect(masked).toMatch(/^[\w-]*={0,2}$/u);
    expect(unmask(masked)).toBe(csrfToken);
  });
});
