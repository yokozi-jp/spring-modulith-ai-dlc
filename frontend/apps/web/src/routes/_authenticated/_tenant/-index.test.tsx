/* @vitest-environment jsdom */

import { getListOrdersMockHandler } from "@frontend/api/mocks/ordering/ordering.msw";
import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vite-plus/test";

import { server } from "@/testing/msw";
import { renderRoute } from "@/testing/render-route";

describe("ホームの画面", () => {
  beforeEach(() => {
    globalThis.sessionStorage.clear();
  });

  it("アプリシェルの中にホームの画面を出す", async () => {
    await renderRoute("/", { locale: "en" });

    const banner = await screen.findByRole("banner");
    expect(banner.textContent).toContain("Demo application");
    expect(screen.getByRole("main").contains(screen.getByRole("heading", { level: 1 }))).toBe(true);
  });

  it("言語を title と lang に反映し、カウンターを押すと数を 1 増やす", async () => {
    const { user } = await renderRoute("/", { locale: "en" });

    const counter = await screen.findByRole("button", { name: "Count: 0" });
    await user.click(counter);

    expect(screen.getByRole("button", { name: "Count: 1" })).toBeTruthy();
    await waitFor(() => {
      expect(document.title).toBe("Demo application");
    });
    expect(document.documentElement.lang).toBe("en");
  });

  it("保存した戻り先があれば、その画面へ移し、1 回で消す", async () => {
    server.use(getListOrdersMockHandler());
    globalThis.sessionStorage.setItem("auth.returnTo", "/orders?page=2#top");

    const { router } = await renderRoute("/", { locale: "en" });

    expect(router.state.location.pathname).toBe("/orders");
    expect(router.state.location.href).toBe("/orders?page=2#top");
    expect(globalThis.sessionStorage.getItem("auth.returnTo")).toBeNull();
  });

  it("保存した値が使えなければ `/` のまま表示し、値を消す", async () => {
    globalThis.sessionStorage.setItem("auth.returnTo", "//evil.example");

    const { router } = await renderRoute("/", { locale: "en" });

    expect(router.state.location.pathname).toBe("/");
    expect(globalThis.sessionStorage.getItem("auth.returnTo")).toBeNull();
  });

  it("戻り先がなければ `/` のまま表示する", async () => {
    const { router } = await renderRoute("/", { locale: "en" });

    expect(router.state.location.pathname).toBe("/");
  });
});
