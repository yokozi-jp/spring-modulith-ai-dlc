/* @vitest-environment jsdom */

import { routeTree } from "@/routeTree.gen";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

describe("home route", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("reflects the locale and increments the counter", async () => {
    Object.defineProperty(globalThis.navigator, "languages", {
      configurable: true,
      value: ["en-US"],
    });
    vi.spyOn(globalThis, "scrollTo").mockReturnValue();
    const router = createRouter({
      history: createMemoryHistory({ initialEntries: ["/"] }),
      routeTree,
      context: { queryClient: new QueryClient() },
    });
    const user = userEvent.setup();

    render(<RouterProvider router={router} />);

    const counter = await screen.findByRole("button", { name: "Count: 0" });
    await user.click(counter);

    expect(screen.getByRole("button", { name: "Count: 1" })).toBeTruthy();
    await waitFor(() => {
      expect(document.title).toBe("Demo application");
    });
    expect(document.documentElement.lang).toBe("en");
  });
});
