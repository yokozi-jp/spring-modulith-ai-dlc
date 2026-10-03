/* @vitest-environment jsdom */

import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { i18n } from "@/i18n";
import { routeTree } from "@/routeTree.gen";

describe("home route", () => {
  afterEach(async () => {
    cleanup();
    vi.restoreAllMocks();
    await i18n.changeLanguage("ja");
  });

  it("renders the home page inside the app shell", async () => {
    await i18n.changeLanguage("en");
    vi.spyOn(globalThis, "scrollTo").mockReturnValue();
    const router = createRouter({
      history: createMemoryHistory({ initialEntries: ["/"] }),
      routeTree,
      context: { queryClient: new QueryClient() },
    });

    render(
      <I18nextProvider i18n={i18n}>
        <RouterProvider router={router} />
      </I18nextProvider>,
    );

    const banner = await screen.findByRole("banner");
    expect(banner.textContent).toContain("Demo application");
    expect(screen.getByRole("main").contains(screen.getByRole("heading", { level: 1 }))).toBe(true);
  });

  it("reflects the locale and increments the counter", async () => {
    await i18n.changeLanguage("en");
    vi.spyOn(globalThis, "scrollTo").mockReturnValue();
    const router = createRouter({
      history: createMemoryHistory({ initialEntries: ["/"] }),
      routeTree,
      context: { queryClient: new QueryClient() },
    });
    const user = userEvent.setup();

    render(
      <I18nextProvider i18n={i18n}>
        <RouterProvider router={router} />
      </I18nextProvider>,
    );

    const counter = await screen.findByRole("button", { name: "Count: 0" });
    await user.click(counter);

    expect(screen.getByRole("button", { name: "Count: 1" })).toBeTruthy();
    await waitFor(() => {
      expect(document.title).toBe("Demo application");
    });
    expect(document.documentElement.lang).toBe("en");
  });
});
