import { describe, expect, it } from "vite-plus/test";
import { resolveLocale, resolveMessages } from "./i18n.ts";

describe("resolveLocale", () => {
  it("地域付き英語を英語へ解決する", () => {
    expect(resolveLocale(["en-US"])).toBe("en");
  });

  it("優先順で最初の対応言語を選ぶ", () => {
    expect(resolveLocale(["fr-FR", "ja-JP", "en-US"])).toBe("ja");
  });

  it("未対応または不正な言語だけなら日本語へ戻す", () => {
    expect(resolveLocale(["invalid_locale", "fr-FR"])).toBe("ja");
  });
});

describe("resolveMessages", () => {
  it("選択した言語で数値を整形する", () => {
    const { locale, messages } = resolveMessages(["en-GB"]);

    expect(locale).toBe("en");
    expect(messages.count(1_234)).toBe("Count: 1,234");
  });
});
