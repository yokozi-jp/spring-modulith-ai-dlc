import { describe, expect, it } from "vite-plus/test";

import { resolveLocale } from "./resolve-locale.ts";

describe("resolveLocale", () => {
  it("地域付き英語を英語へ解決する", () => {
    expect(resolveLocale(["en-US"])).toBe("en");
  });

  it("優先順で最初の対応言語を選ぶ", () => {
    expect(resolveLocale(["fr-FR", "ja-JP", "en-US"])).toBe("ja");
  });

  it("完全一致する後続の候補より優先順を優先する", () => {
    expect(resolveLocale(["ja-JP", "en"])).toBe("ja");
  });

  it("未対応または不正な言語だけなら日本語へ戻す", () => {
    expect(resolveLocale(["invalid_locale", "fr-FR"])).toBe("ja");
  });
});
