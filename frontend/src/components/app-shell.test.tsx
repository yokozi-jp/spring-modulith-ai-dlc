/* @vitest-environment jsdom */

import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { replaceTelemetryForTesting } from "@/lib/telemetry";
import { renderRoute } from "@/testing/render-route";

type Sink = NonNullable<Parameters<typeof replaceTelemetryForTesting>[0]>;

const cookieValue = "0b1e5c1a-4f0e-4c55-9d8e-2f1a3b4c5d6e";

function noop(): void {
  // native submit の画面遷移を止める。
}

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

  it("switches the telemetry session before one native submit", async () => {
    const user = userEvent.setup();
    const calls: string[] = [];
    const nativeSubmit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(() => {
      calls.push("submit");
    });
    replaceTelemetryForTesting({
      api: {
        pushError: vi.fn<Sink["api"]["pushError"]>(),
        setSession: (session) => {
          calls.push(`set:${session?.id ?? ""}`);
        },
      },
      pause: () => {
        calls.push("pause");
      },
      unpause: () => {
        calls.push("unpause");
      },
    });
    const form = await renderLogoutForm();

    await user.click(screen.getByRole("button", { name: "Log out" }));
    form.dispatchEvent(new SubmitEvent("submit", { bubbles: true, cancelable: true }));

    await vi.waitFor(() => {
      expect(calls).toStrictEqual(["pause", "set:Abc2345678", "unpause", "submit"]);
    });
    expect(nativeSubmit.mock.instances).toStrictEqual([form]);
  });

  it("submits even when the telemetry session switch fails", async () => {
    const user = userEvent.setup();
    const nativeSubmit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(noop);
    const unpause = vi.fn<() => void>();
    replaceTelemetryForTesting({
      api: {
        pushError: vi.fn<Sink["api"]["pushError"]>(),
        setSession: () => {
          throw new Error("failed");
        },
      },
      unpause,
    });
    await renderLogoutForm();

    await user.click(screen.getByRole("button", { name: "Log out" }));

    await vi.waitFor(() => {
      expect(nativeSubmit).toHaveBeenCalledOnce();
    });
    expect(unpause).not.toHaveBeenCalled();
  });

  it("allows logout again after the page returns from the back/forward cache", async () => {
    const user = userEvent.setup();
    const nativeSubmit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(noop);
    replaceTelemetryForTesting({ api: { pushError: vi.fn<Sink["api"]["pushError"]>() } });
    await renderLogoutForm();
    const button = screen.getByRole("button", { name: "Log out" });

    await user.click(button);
    await vi.waitFor(() => {
      expect(nativeSubmit).toHaveBeenCalledOnce();
    });
    globalThis.dispatchEvent(new PageTransitionEvent("pageshow", { persisted: true }));
    await user.click(button);

    await vi.waitFor(() => {
      expect(nativeSubmit).toHaveBeenCalledTimes(2);
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
