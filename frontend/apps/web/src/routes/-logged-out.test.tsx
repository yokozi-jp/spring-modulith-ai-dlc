/* @vitest-environment jsdom */

import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

describe("logged-out route", () => {
  it("renders the logged-out page with a full-navigation login link outside the app shell", async () => {
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
});
