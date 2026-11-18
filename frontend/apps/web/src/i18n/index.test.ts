/* @vitest-environment jsdom */

import { describe, expect, it } from "vite-plus/test";

import { i18n } from "./index.ts";

describe("i18n", () => {
  it("選択した言語で数値を整形する", () => {
    expect(i18n.t("count", { count: 1234, lng: "en" })).toBe("Count: 1,234");
  });
});
