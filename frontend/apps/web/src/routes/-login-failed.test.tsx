/* @vitest-environment jsdom */

import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

describe("ログインの失敗の画面", () => {
  it.each([
    {
      locale: "ja" as const,
      heading: "ログインできませんでした",
      description: "もう一度お試しください。",
      link: "もう一度ログイン",
      title: "デモアプリケーション",
    },
    {
      locale: "en" as const,
      heading: "Login failed",
      description: "Please try again.",
      link: "Log in again",
      title: "Demo application",
    },
  ])(
    "$locale：アプリシェルの外に、失敗したことと、ページ全体の遷移でログインし直すリンクを出す",
    async ({ locale, heading, description, link, title }) => {
      await renderRoute("/login-failed", { locale });

      await expect(screen.findByRole("heading", { level: 1, name: heading })).resolves.toBeTruthy();
      expect(screen.getByText(description)).toBeTruthy();
      expect(screen.getByRole("link", { name: link }).getAttribute("href")).toBe(
        "/oauth2/authorization/web",
      );
      expect(screen.queryByRole("banner")).toBeNull();
      await waitFor(() => {
        expect(document.title).toBe(title);
      });
    },
  );

  it("ほかの画面へ移らず、もう一度ログインした後に戻る先を残す", async () => {
    globalThis.sessionStorage.setItem("auth.returnTo", "/orders/new");

    const { router } = await renderRoute("/login-failed", { locale: "en" });

    await expect(
      screen.findByRole("heading", { level: 1, name: "Login failed" }),
    ).resolves.toBeTruthy();
    expect(router.state.location.pathname).toBe("/login-failed");
    expect(globalThis.sessionStorage.getItem("auth.returnTo")).toBe("/orders/new");
  });
});
