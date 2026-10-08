import type { Page } from "@playwright/test";

import { expect } from "./fixtures";

// page.route で集めた /collect の本文（Faro の公開 wire payload）を読む helper。

export async function waitForTelemetry(
  payloads: string[],
  value: string,
  startIndex = 0,
): Promise<void> {
  await expect
    .poll(() => payloads.slice(startIndex).some((payload) => payload.includes(value)), {
      timeout: 15_000,
    })
    .toBe(true);
}

export function sessionId(payload: string): string {
  const match = /"session":\{[^}]*"id":"(?<id>[^"]+)"/u.exec(payload);
  const id = match?.groups?.id;
  if (typeof id !== "string" || id === "") {
    throw new Error("Faro payload に session ID がありません");
  }
  return id;
}

async function throwBrowserError(page: Page, message: string): Promise<void> {
  await page.evaluate((errorMessage) => {
    setTimeout(() => {
      throw new Error(errorMessage);
    }, 0);
  }, message);
}

function observeInp(page: Page): Promise<boolean> {
  return page.evaluate(
    () =>
      // oxlint-disable-next-line promise/avoid-new -- PerformanceObserver の callback を browser 側で待つ。
      new Promise<boolean>((resolve) => {
        const observer = new PerformanceObserver((list) => {
          const interacted = list
            .getEntries()
            .some(
              (entry) =>
                "interactionId" in entry &&
                typeof entry.interactionId === "number" &&
                entry.interactionId > 0,
            );
          if (interacted) {
            observer.disconnect();
            resolve(true);
          }
        });
        observer.observe({ type: "event", buffered: true });
        setTimeout(() => {
          resolve(false);
        }, 5000);
      }),
  );
}

function observeCls(page: Page): Promise<boolean> {
  return page.evaluate(
    () =>
      // oxlint-disable-next-line promise/avoid-new -- PerformanceObserver の callback を browser 側で待つ。
      new Promise<boolean>((resolve) => {
        const observer = new PerformanceObserver((list) => {
          const shifted = list
            .getEntries()
            .some((entry) => "hadRecentInput" in entry && entry.hadRecentInput === false);
          if (shifted) {
            observer.disconnect();
            resolve(true);
          }
        });
        observer.observe({ type: "layout-shift", buffered: true });
        setTimeout(() => {
          const element = document.createElement("div");
          element.style.height = "100px";
          document.querySelector("main")?.prepend(element);
        }, 600);
        setTimeout(() => {
          resolve(false);
        }, 5000);
      }),
  );
}

async function addInpProbe(page: Page): Promise<void> {
  await page.evaluate(() => {
    const button = document.createElement("button");
    button.textContent = "INP probe";
    button.addEventListener("click", () => {
      const end = performance.now() + 200;
      while (performance.now() < end) {
        // 実際の click に Event Timing entry ができるまで main thread を占有する。
      }
    });
    document.querySelector("main")?.prepend(button);
  });
}

async function clickInpProbe(page: Page): Promise<void> {
  const inpProbe = page.getByRole("button", { name: "INP probe" });
  await inpProbe.click();
  await inpProbe.click();
  await inpProbe.click();
}

async function exerciseWebVitals(page: Page, payloads: string[]): Promise<void> {
  const startIndex = payloads.length;
  await addInpProbe(page);
  const inpObserved = observeInp(page);
  await clickInpProbe(page);
  expect(await inpObserved).toBe(true);
  expect(await observeCls(page)).toBe(true);
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await Promise.all(
    ['"lcp"', '"inp"', '"cls"'].map((vital) => waitForTelemetry(payloads, vital, startIndex)),
  );
}

// 一つの匿名 session で送る例外と画面遷移の目印と、待ち始める payload の位置。
interface SessionRun {
  readonly payloads: string[];
  readonly error: string;
  readonly view: string;
  readonly startIndex: number;
}

const sessionSignals = ({ error, view }: SessionRun): string[] => [
  error,
  view,
  '"lcp"',
  '"inp"',
  '"cls"',
];

export async function collectTelemetry(page: Page): Promise<string[]> {
  const payloads: string[] = [];
  await page.route("**/collect", async (route) => {
    payloads.push(route.request().postData() ?? "");
    await route.fulfill({ status: 202 });
  });
  return payloads;
}

// 目印のどれかを含む payload がすべての目印をそろえ、一つの session ID だけを持つことを確かめて返す。
function expectSingleSession(run: SessionRun): string {
  const signals = sessionSignals(run);
  const matching = run.payloads
    .slice(run.startIndex)
    .filter((payload) => signals.some((signal) => payload.includes(signal)));
  for (const signal of signals) {
    expect(matching.some((payload) => payload.includes(signal))).toBe(true);
  }
  const sessions = [...new Set(matching.map((payload) => sessionId(payload)))];
  expect(sessions).toHaveLength(1);
  return sessions[0] ?? "";
}

// 画面遷移を待ってから Web Vitals と例外を送り、それらが一つの session ID を持つことを確かめて返す。
export async function exerciseSession(page: Page, run: SessionRun): Promise<string> {
  await waitForTelemetry(run.payloads, run.view, run.startIndex);
  await exerciseWebVitals(page, run.payloads);
  await throwBrowserError(page, run.error);
  await waitForTelemetry(run.payloads, run.error, run.startIndex);
  return expectSingleSession(run);
}

// 1 件の payload は 1 種類の signal だけを持ち、URL の query や fragment、利用者の情報を含まない。
export function expectWireLimits(payloads: string[]): void {
  for (const payload of payloads) {
    const itemCount = ["exceptions", "events", "measurements"].filter((key) =>
      new RegExp(`"${key}":\\[[^\\]]`, "u").test(payload),
    ).length;
    expect(itemCount).toBeLessThanOrEqual(1);
  }
  expect(payloads.join("\n")).not.toMatch(
    /secret-query|secret-fragment|APP_SESSION|person@example\.com|isSampled|unknown|#secret-selector/u,
  );
}
