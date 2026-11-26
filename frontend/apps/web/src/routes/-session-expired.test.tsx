/* @vitest-environment jsdom */

import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it, onTestFinished } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

describe("ログインの有効期限切れの画面", () => {
  it.each([
    {
      locale: "ja" as const,
      heading: "ログインの有効期限が切れました",
      description: "もう一度ログインしてください。直前の操作は完了していない可能性があります。",
      link: "もう一度ログイン",
      title: "デモアプリケーション",
    },
    {
      locale: "en" as const,
      heading: "Your login has expired",
      description: "Please log in again. Your last action may not have completed.",
      link: "Log in again",
      title: "Demo application",
    },
  ])(
    "$locale：アプリシェルの外に、有効期限が切れたことと、ページ全体の遷移でログインし直すリンクを出す",
    async ({ locale, heading, description, link, title }) => {
      await renderRoute("/session-expired", { locale });

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
    onTestFinished(() => {
      globalThis.sessionStorage.removeItem("auth.returnTo");
    });

    const { router } = await renderRoute("/session-expired", { locale: "en" });

    await expect(
      screen.findByRole("heading", { level: 1, name: "Your login has expired" }),
    ).resolves.toBeTruthy();
    expect(router.state.location.pathname).toBe("/session-expired");
    expect(globalThis.sessionStorage.getItem("auth.returnTo")).toBe("/orders/new");
  });
});
