/* @vitest-environment jsdom */

import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

describe("home route", () => {
  it("renders the home page inside the app shell", async () => {
    await renderRoute("/", { locale: "en" });

    const banner = await screen.findByRole("banner");
    expect(banner.textContent).toContain("Demo application");
    expect(screen.getByRole("main").contains(screen.getByRole("heading", { level: 1 }))).toBe(true);
  });

  it("reflects the locale and increments the counter", async () => {
    const { user } = await renderRoute("/", { locale: "en" });

    const counter = await screen.findByRole("button", { name: "Count: 0" });
    await user.click(counter);

    expect(screen.getByRole("button", { name: "Count: 1" })).toBeTruthy();
    await waitFor(() => {
      expect(document.title).toBe("Demo application");
    });
    expect(document.documentElement.lang).toBe("en");
  });
});
