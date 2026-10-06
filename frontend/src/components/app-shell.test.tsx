/* @vitest-environment jsdom */

import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

const cookieValue = "0b1e5c1a-4f0e-4c55-9d8e-2f1a3b4c5d6e";

async function renderLogoutForm() {
  await renderRoute("/", { locale: "en" });
  const button = await screen.findByRole("button", { name: "Log out" });
  if (!(button instanceof HTMLButtonElement) || !button.form) {
    throw new Error("Log out button is not inside a form");
  }
  return button.form;
}

describe("app shell logout form", () => {
  it("posts to /logout with a hidden CSRF token made from __Host-XSRF-TOKEN", async () => {
    vi.spyOn(document, "cookie", "get").mockReturnValue(
      `XSRF-TOKEN=old; __Host-XSRF-TOKEN=${cookieValue}`,
    );

    const form = await renderLogoutForm();

    expect(form.getAttribute("method")).toBe("post");
    expect(form.getAttribute("action")).toBe("/logout");
    const field = form.elements.namedItem("_csrf");
    if (!(field instanceof HTMLInputElement) || field.type !== "hidden") {
      throw new TypeError("hidden _csrf input not found");
    }
    // マスクした値は乱数と XOR の結果を並べるので、元の token の 2 倍の長さになる（戻せることは csrf.test.ts で確かめる）。
    expect(atob(field.value.replaceAll("-", "+").replaceAll("_", "/"))).toHaveLength(
      cookieValue.length * 2,
    );
  });
});
