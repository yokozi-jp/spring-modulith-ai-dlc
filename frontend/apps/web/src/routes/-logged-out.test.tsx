/* @vitest-environment jsdom */

import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

describe("ログアウト後の画面", () => {
  it("アプリシェルの外に、ログアウトしたことと、ページ全体の遷移でログインし直すリンクを出す", async () => {
    await renderRoute("/logged-out", { locale: "en" });

    await expect(
      screen.findByRole("heading", { level: 1, name: "You have been logged out" }),
    ).resolves.toBeTruthy();
    expect(screen.getByRole("link", { name: "Log in again" }).getAttribute("href")).toBe(
      "/oauth2/authorization/web",
    );
    expect(screen.queryByRole("banner")).toBeNull();
    await waitFor(() => {
      expect(document.title).toBe("Demo application");
    });
  });

  it("保存した戻り先を、利用者をまたいで残さないよう消す", async () => {
    globalThis.sessionStorage.setItem("auth.returnTo", "/orders/new");

    await renderRoute("/logged-out", { locale: "en" });

    expect(globalThis.sessionStorage.getItem("auth.returnTo")).toBeNull();
  });
});
