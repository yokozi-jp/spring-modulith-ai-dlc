/* @vitest-environment jsdom */

import { screen } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

describe("not found の表示", () => {
  it("どの route にも一致しない URL では、アプリシェルを出さず、自分で main を持つ", async () => {
    await renderRoute("/no-such-page");

    const heading = await screen.findByRole("heading", { name: "ページが見つかりません" });
    expect(screen.getByRole("main")).toContainElement(heading);
    expect(screen.queryByRole("button", { name: "ログアウト" })).toBeNull();
  });

  it.each(["/orders/new/foo", "/members/foo"])(
    "ログインが要る route の配下の %s では、アプリシェルの main の中に出す",
    async (path) => {
      await renderRoute(path);

      const heading = await screen.findByRole("heading", { name: "ページが見つかりません" });
      expect(screen.getByRole("main")).toContainElement(heading);
      expect(screen.getByRole("button", { name: "ログアウト" })).toBeTruthy();
    },
  );
});
