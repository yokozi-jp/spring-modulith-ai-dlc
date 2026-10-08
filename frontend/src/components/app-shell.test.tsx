/* oxlint-disable eslint/max-statements, eslint/init-declarations, promise/avoid-new, vitest/prefer-mock-return-shorthand, unicorn/no-useless-undefined, vitest/require-mock-type-parameters, typescript/strict-void-return -- 制御可能な Promise と native submit の spy で logout の順序を検査する。 */
/* @vitest-environment jsdom */

import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { replaceTelemetryForTesting } from "@/lib/telemetry";
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
  afterEach(() => {
    replaceTelemetryForTesting();
  });

  it("waits for the telemetry session reset and submits only once", async () => {
    const user = userEvent.setup();
    let finishReset: (() => void) | undefined;
    const resetSession = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishReset = resolve;
        }),
    );
    const nativeSubmit = vi
      .spyOn(HTMLFormElement.prototype, "submit")
      .mockImplementation(() => undefined);
    replaceTelemetryForTesting({ api: { pushError: vi.fn() }, resetSession });
    const form = await renderLogoutForm();
    const button = screen.getByRole("button", { name: "Log out" });

    await user.click(button);
    form.dispatchEvent(new SubmitEvent("submit", { bubbles: true, cancelable: true }));
    expect(resetSession).toHaveBeenCalledOnce();
    expect(nativeSubmit).not.toHaveBeenCalled();

    finishReset?.();
    await vi.waitFor(() => {
      expect(nativeSubmit).toHaveBeenCalledOnce();
    });
    expect(nativeSubmit.mock.instances[0]).toBe(form);
  });

  it("submits even when the telemetry session reset fails", async () => {
    const user = userEvent.setup();
    const nativeSubmit = vi
      .spyOn(HTMLFormElement.prototype, "submit")
      .mockImplementation(() => undefined);
    replaceTelemetryForTesting({
      api: { pushError: vi.fn() },
      resetSession: vi.fn<() => Promise<void>>().mockRejectedValue(new Error("failed")),
    });
    await renderLogoutForm();

    await user.click(screen.getByRole("button", { name: "Log out" }));

    await vi.waitFor(() => {
      expect(nativeSubmit).toHaveBeenCalledOnce();
    });
  });

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
