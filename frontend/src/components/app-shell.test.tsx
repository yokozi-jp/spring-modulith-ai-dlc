/* @vitest-environment jsdom */

import { QueryClient } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import { cleanup, render, screen } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { i18n } from "@/i18n";
import { routeTree } from "@/routeTree.gen";

const csrfToken = "0b1e5c1a-4f0e-4c55-9d8e-2f1a3b4c5d6e";

// XorCsrfTokenRequestAttributeHandler と同じく、base64url を decode して前半と後半を XOR する。
function unmask(value: string) {
  const decoded = Uint8Array.from(
    atob(value.replaceAll("-", "+").replaceAll("_", "/")),
    (char) => char.codePointAt(0) ?? 0,
  );
  const half = decoded.length / 2;
  const random = decoded.slice(0, half);
  // oxlint-disable-next-line eslint/no-bitwise -- Spring Security のマスクは XOR で定義されている。
  const token = decoded.slice(half).map((byte, index) => byte ^ (random[index] ?? 0));
  return new TextDecoder().decode(token);
}

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

  it("posts to /logout with the XSRF-TOKEN cookie masked as Spring Security expects", async () => {
    await i18n.changeLanguage("en");
    vi.spyOn(document, "cookie", "get").mockReturnValue(`other=1; XSRF-TOKEN=${csrfToken}`);

    const form = await renderLogoutForm();

    expect(form.getAttribute("method")).toBe("post");
    expect(form.getAttribute("action")).toBe("/logout");
    const field = form.elements.namedItem("_csrf");
    if (!(field instanceof HTMLInputElement) || field.type !== "hidden") {
      throw new TypeError("hidden _csrf input not found");
    }
    expect(field.value).not.toBe(csrfToken);
    expect(unmask(field.value)).toBe(csrfToken);
  });
});
