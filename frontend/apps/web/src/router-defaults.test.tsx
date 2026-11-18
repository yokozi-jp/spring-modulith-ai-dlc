/* @vitest-environment jsdom */

import { QueryClient } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from "@tanstack/react-router";
import type { AnyRoute } from "@tanstack/react-router";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vite-plus/test";

import { i18n } from "@/i18n";
import { replaceTelemetryForTesting } from "@/lib/telemetry";
import { routerDefaults } from "@/router-defaults";
import { routeTree } from "@/routeTree.gen";
import { createGate } from "@/testing/gate";

type Sink = NonNullable<Parameters<typeof replaceTelemetryForTesting>[0]>;

function LoadedPage() {
  return <p>loaded</p>;
}

function treeWithLoader(loader: () => Promise<unknown>) {
  const rootRoute = createRootRoute();
  return rootRoute.addChildren([
    createRoute({ getParentRoute: () => rootRoute, path: "/", loader, component: LoadedPage }),
  ]);
}

function renderRouter(tree: AnyRoute, path: string, defaultPendingMs?: number) {
  const router = createRouter({
    history: createMemoryHistory({ initialEntries: [path] }),
    routeTree: tree,
    context: { queryClient: new QueryClient() },
    ...routerDefaults,
    ...(defaultPendingMs === undefined ? {} : { defaultPendingMs }),
  });
  render(
    <I18nextProvider i18n={i18n}>
      <RouterProvider router={router} />
    </I18nextProvider>,
  );
}

describe("router の既定値", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("en");
    vi.spyOn(globalThis, "scrollTo").mockReturnValue();
  });

  afterEach(() => {
    replaceTelemetryForTesting();
  });

  it("存在しない path では、言語に合わせた not found の表示と、ホームへ戻るリンクを出す", async () => {
    renderRouter(routeTree, "/no-such-page");

    await expect(screen.findByRole("heading", { name: "Page not found" })).resolves.toBeTruthy();
    expect(screen.getByRole("link", { name: "Back to home" }).getAttribute("href")).toBe("/");
  });

  it("loader が失敗したらエラーの詳細を出さず、再試行すると読み込んだ画面を出す", async () => {
    // The router and React report the expected loader error to the console.
    vi.spyOn(console, "warn").mockReturnValue();
    vi.spyOn(console, "error").mockReturnValue();
    const loader = vi
      .fn<() => Promise<void>>()
      .mockRejectedValueOnce(new Error("internal detail"))
      .mockResolvedValue();
    renderRouter(treeWithLoader(loader), "/");

    await expect(
      screen.findByRole("heading", { name: "Something went wrong" }),
    ).resolves.toBeTruthy();
    expect(screen.getByText("The page could not be displayed. Try again.")).toBeTruthy();
    expect(screen.queryByText("internal detail")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Try again" }));

    await expect(screen.findByText("loaded")).resolves.toBeTruthy();
    expect(loader).toHaveBeenCalledTimes(2);
  });

  it("loader が投げたエラーをテレメトリへ送る", async () => {
    vi.spyOn(console, "warn").mockReturnValue();
    vi.spyOn(console, "error").mockReturnValue();
    const pushError = vi.fn<Sink["api"]["pushError"]>();
    replaceTelemetryForTesting({ api: { pushError } });
    const error = new Error("internal detail");
    renderRouter(
      treeWithLoader(() => Promise.reject(error)),
      "/",
    );

    await expect.poll(() => pushError).toHaveBeenCalledWith(error);
  });

  it("loader の実行中は、言語に合わせた読み込み中の表示を出す", async () => {
    const loader = createGate();
    renderRouter(
      treeWithLoader(() => loader.promise),
      "/",
      0,
    );

    await expect(screen.findByRole("status")).resolves.toHaveProperty("textContent", "Loading…");

    loader.release();
    await expect(screen.findByText("loaded")).resolves.toBeTruthy();
  });
});
