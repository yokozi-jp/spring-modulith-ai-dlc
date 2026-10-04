/* @vitest-environment jsdom */

import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { i18n } from "@/i18n";
import { routeTree } from "@/routeTree.gen";

describe("logged-out route", () => {
  afterEach(async () => {
    cleanup();
    vi.restoreAllMocks();
    await i18n.changeLanguage("ja");
  });

  it("renders the logged-out page with a full-navigation login link outside the app shell", async () => {
    await i18n.changeLanguage("en");
    vi.spyOn(globalThis, "scrollTo").mockReturnValue();
    const router = createRouter({
      history: createMemoryHistory({ initialEntries: ["/logged-out"] }),
      routeTree,
      context: { queryClient: new QueryClient() },
    });

    render(
      <I18nextProvider i18n={i18n}>
        <RouterProvider router={router} />
      </I18nextProvider>,
    );

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
});
