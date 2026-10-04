/* @vitest-environment jsdom */

import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { i18n } from "@/i18n";
import { routeTree } from "@/routeTree.gen";

async function renderLogoutForm() {
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
  const button = await screen.findByRole("button", { name: "Log out" });
  if (!(button instanceof HTMLButtonElement) || !button.form) {
    throw new Error("Log out button is not inside a form");
  }
  return button.form;
}

describe("app shell logout form", () => {
  afterEach(async () => {
    cleanup();
    vi.restoreAllMocks();
    await i18n.changeLanguage("ja");
  });

  it("posts to /logout with a hidden CSRF token", async () => {
    await i18n.changeLanguage("en");
    vi.spyOn(document, "cookie", "get").mockReturnValue("XSRF-TOKEN=token");

    const form = await renderLogoutForm();

    expect(form.getAttribute("method")).toBe("post");
    expect(form.getAttribute("action")).toBe("/logout");
    const field = form.elements.namedItem("_csrf");
    if (!(field instanceof HTMLInputElement) || field.type !== "hidden") {
      throw new TypeError("hidden _csrf input not found");
    }
    expect(field.value).not.toBe("");
  });
});
