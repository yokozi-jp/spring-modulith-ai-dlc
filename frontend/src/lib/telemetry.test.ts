/* oxlint-disable eslint/max-statements, eslint/max-lines, vitest/prefer-called-exactly-once-with, eslint/arrow-body-style, eslint/init-declarations, unicorn/prefer-string-raw, unicorn/max-nested-calls, eslint/curly, vitest/require-mock-type-parameters, typescript/strict-void-return, typescript/no-confusing-void-expression -- SDK を import しない公開境界の契約テストでは最小の fake を使う。 */
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import {
  bindRouterTelemetry,
  replaceTelemetryForTesting,
  reportCaughtError,
  resetTelemetrySession,
  sanitizeTelemetryItem,
  untracedUrl,
} from "./telemetry";

type TransportItem = Parameters<typeof sanitizeTelemetryItem>[0];
type Sink = NonNullable<Parameters<typeof replaceTelemetryForTesting>[0]>;
type Router = Parameters<typeof bindRouterTelemetry>[0];

// SDK の enum をテストへ import しない。
const item = (type: string, payload: object, meta: object = {}): TransportItem => {
  // oxlint-disable-next-line typescript/no-unsafe-type-assertion -- 公開 wire 値から SDK の union を組み立てる。
  return { type, payload, meta } as TransportItem;
};

const exceptionItem = (value: string, filename: string): TransportItem =>
  item(
    "exception",
    {
      type: "Error",
      value,
      timestamp: "2026-10-06T00:00:00.000Z",
      fatal: false,
      stacktrace: { frames: [{ filename, function: "render", lineno: 10, colno: 5 }] },
    },
    {
      app: { name: "demo-web", namespace: "demo", version: "1.0.0", environment: "local" },
      session: { id: "Abc2345678", attributes: { isSampled: "true", secret: "x" } },
      view: { name: "/" },
      page: { url: "http://localhost:5173/orders/42?a=1#b" },
      user: { email: "person@example.com" },
    },
  );

function router(fullPath: string) {
  let listener: ((event: { readonly hrefChanged: boolean }) => void) | undefined;
  const source: Router = {
    state: { matches: [{ fullPath }] },
    subscribe: (_event, next) => {
      listener = next;
      return vi.fn();
    },
  };
  return { source, resolve: (hrefChanged = true) => listener?.({ hrefChanged }) };
}

describe("sanitizeTelemetryItem", () => {
  afterEach(() => {
    replaceTelemetryForTesting();
  });

  it.each([
    ["https://host/orders/42?token=x#f", "[redacted-url]"],
    ["HTTPS://HOST/orders/42?token=x#f", "[redacted-url]"],
    ["blob:https://host/orders/42?token=x#f", "[redacted-url]"],
    ["file:///orders/42?token=x#f", "[redacted-url]"],
    ["webpack://app/orders/42?token=x#f", "[redacted-url]"],
    ["data:text/plain,secret-query#secret-fragment", "[redacted-url]"],
    ["[/orders/42?token=x#f", "[[redacted-url]"],
    ["(/orders/42?token=x#f", "([redacted-url]"],
    ['"/orders/42?token=x#f', '"[redacted-url]'],
    [",/orders/42?token=x#f", ",[redacted-url]"],
    [":/orders/42?token=x#f", ":[redacted-url]"],
    ["：/orders/42?token=x#f", "：[redacted-url]"],
    ["、/orders/42?token=x#f", "、[redacted-url]"],
    ["。/orders/42?token=x#f", "。[redacted-url]"],
    ["日本語/orders/42?token=x#f", "日本語[redacted-url]"],
    ["/one?q=1 /two#f", "[redacted-url] [redacted-url]"],
    ["https://host/x?q=1\\nnext", "[redacted-url]\\nnext"],
    ["a/b?c", "a/b?c"],
  ])("replaces the complete URL token in %s", (value, expected) => {
    replaceTelemetryForTesting({ api: { pushError: vi.fn() }, trustedRoutes: ["/"] });
    const sanitized = sanitizeTelemetryItem(exceptionItem(value, value));

    expect(sanitized?.payload).toMatchObject({
      value: expected,
      stacktrace: { frames: [{ filename: expected, lineno: 10, colno: 5 }] },
    });
    expect(JSON.stringify(sanitized)).not.toContain("person@example.com");
  });

  it("uses only a trusted route template for page metadata", () => {
    replaceTelemetryForTesting({ api: { pushError: vi.fn() }, trustedRoutes: ["/"] });

    expect(sanitizeTelemetryItem(exceptionItem("failed", "app.js"))?.meta).toStrictEqual({
      app: { name: "demo-web", namespace: "demo", version: "1.0.0", environment: "local" },
      session: { id: "Abc2345678", attributes: { isSampled: "true" } },
      view: { name: "/" },
      page: { url: "/" },
    });
  });

  it("keeps an initial root view without the unknown source", () => {
    replaceTelemetryForTesting({
      api: { pushError: vi.fn() },
      trustedRoutes: ["/"],
    });
    const event = item(
      "event",
      {
        name: "view_changed",
        timestamp: "2026-10-06T00:00:00Z",
        attributes: { fromView: "unknown", toView: "/" },
      },
      exceptionItem("x", "x").meta,
    );

    expect(sanitizeTelemetryItem(event)?.payload).toStrictEqual({
      name: "view_changed",
      timestamp: "2026-10-06T00:00:00Z",
      attributes: { toView: "/" },
    });
  });

  it("keeps only trusted route templates in a view transition", () => {
    replaceTelemetryForTesting({
      api: { pushError: vi.fn() },
      trustedRoutes: ["/", "/logged-out"],
    });
    const event = item(
      "event",
      {
        name: "view_changed",
        timestamp: "2026-10-06T00:00:00Z",
        attributes: { fromView: "/", toView: "/logged-out" },
      },
      exceptionItem("x", "x").meta,
    );

    expect(sanitizeTelemetryItem(event)?.payload).toStrictEqual({
      name: "view_changed",
      timestamp: "2026-10-06T00:00:00Z",
      attributes: { fromView: "/", toView: "/logged-out" },
    });
  });

  it.each([
    [{ lcp: 120, delta: 3 }, "lcp", 120],
    [{ inp: 18, input_delay: 2 }, "inp", 18],
    [{ cls: 0.12, element: "#secret" }, "cls", 0.12],
  ])("keeps only one Core Web Vital value", (values, name, expected) => {
    replaceTelemetryForTesting({ api: { pushError: vi.fn() }, trustedRoutes: ["/"] });
    const measurement = item(
      "measurement",
      { type: "web-vitals", timestamp: "2026-10-06T00:00:00Z", values, context: { secret: "x" } },
      exceptionItem("x", "x").meta,
    );

    expect(sanitizeTelemetryItem(measurement)?.payload).toStrictEqual({
      type: "web-vitals",
      timestamp: "2026-10-06T00:00:00Z",
      values: { [name]: expected },
    });
  });

  it.each([
    { fcp: 1 },
    { ttfb: 1 },
    { lcp: 1, cls: 0.1 },
    { lcp: -1 },
    { lcp: Number.NaN },
    { lcp: Number.POSITIVE_INFINITY },
  ])("drops an unsupported Web Vital %j", (values) => {
    replaceTelemetryForTesting({ api: { pushError: vi.fn() }, trustedRoutes: ["/"] });
    expect(
      sanitizeTelemetryItem(
        item(
          "measurement",
          { type: "web-vitals", timestamp: "x", values },
          exceptionItem("x", "x").meta,
        ),
      ),
    ).toBeNull();
  });

  it("removes every url.* trace attribute without changing correlation fields", () => {
    const span = {
      traceId: "trace",
      spanId: "span",
      parentSpanId: "parent",
      attributes: [
        { key: "url.full", value: { stringValue: "http://localhost/api/42?q=x" } },
        { key: "url.path", value: { stringValue: "/api/42" } },
        { key: "url.scheme", value: { stringValue: "http" } },
        { key: "http.request.method", value: { stringValue: "GET" } },
      ],
    };
    const trace = item("trace", { resourceSpans: [{ scopeSpans: [{ spans: [span] }] }] });

    expect(sanitizeTelemetryItem(trace)?.payload).toMatchObject({
      resourceSpans: [
        {
          scopeSpans: [
            {
              spans: [
                {
                  traceId: "trace",
                  spanId: "span",
                  parentSpanId: "parent",
                  attributes: [{ key: "http.request.method", value: { stringValue: "GET" } }],
                },
              ],
            },
          ],
        },
      ],
    });
  });

  it("drops an item that cannot be cloned", () => {
    const original = exceptionItem("failed", "app.js");
    Reflect.set(original.payload, "symbol", Symbol("not cloneable"));
    expect(sanitizeTelemetryItem(original)).toBeNull();
  });
});

describe("router telemetry", () => {
  afterEach(() => replaceTelemetryForTesting());

  it("records resolved transitions in order and ignores invalidation", async () => {
    const setView = vi.fn();
    const pushEvent = vi.fn();
    replaceTelemetryForTesting({
      api: { pushError: vi.fn(), setView, pushEvent },
      trustedRoutes: [],
    });
    const fake = router("/");
    const unsubscribe = bindRouterTelemetry(fake.source);

    fake.resolve();
    fake.resolve(false);
    fake.resolve();

    await vi.waitFor(() => expect(setView).toHaveBeenCalledOnce());
    expect(setView).toHaveBeenCalledWith({ name: "/" });
    expect(pushEvent).toHaveBeenCalledOnce();
    expect(pushEvent).toHaveBeenCalledWith(
      "view_changed",
      { fromView: "/", toView: "/" },
      undefined,
      { skipDedupe: true },
    );
    expect(unsubscribe).toBeTypeOf("function");
  });

  it("does not send an untrusted route", () => {
    const setView = vi.fn();
    replaceTelemetryForTesting({ api: { pushError: vi.fn(), setView }, trustedRoutes: [] });
    const fake = router("orders/42?token=x#f");
    bindRouterTelemetry(fake.source);
    fake.resolve();
    expect(setView).not.toHaveBeenCalled();
  });
});

describe("resetTelemetrySession", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    replaceTelemetryForTesting();
  });

  it("pauses, sets an unbiased short id and resumes", async () => {
    const calls: string[] = [];
    const setSession = vi.fn<NonNullable<Sink["api"]["setSession"]>>((session) => {
      calls.push(`set:${session?.id}`);
    });
    const removeItem = vi.fn(() => {
      calls.push("remove");
    });
    vi.stubGlobal("sessionStorage", { removeItem });
    vi.stubGlobal("crypto", { getRandomValues: (bytes: Uint8Array) => bytes.fill(0) });
    replaceTelemetryForTesting({
      api: { pushError: vi.fn(), setSession },
      pause: () => calls.push("pause"),
      unpause: () => calls.push("unpause"),
      trustedRoutes: [],
    });

    await resetTelemetrySession();

    expect(calls).toStrictEqual(["remove", "pause", "set:aaaaaaaaaa", "unpause"]);
  });

  it.each(["random", "setSession"])("stays paused when %s fails", async (failure) => {
    const unpause = vi.fn();
    vi.stubGlobal("sessionStorage", { removeItem: vi.fn() });
    vi.stubGlobal("crypto", {
      getRandomValues: (bytes: Uint8Array) => {
        if (failure === "random") throw new Error("random failed");
        return bytes.fill(0);
      },
    });
    replaceTelemetryForTesting({
      api: {
        pushError: vi.fn(),
        setSession: () => {
          if (failure === "setSession") throw new Error("set failed");
        },
      },
      pause: vi.fn(),
      unpause,
      trustedRoutes: [],
    });

    await expect(resetTelemetrySession()).resolves.toBeUndefined();
    expect(unpause).not.toHaveBeenCalled();
  });

  it("uses the component test session reset seam", async () => {
    const resetSession = vi.fn<() => Promise<void>>().mockResolvedValue();
    replaceTelemetryForTesting({ api: { pushError: vi.fn() }, resetSession, trustedRoutes: [] });
    await resetTelemetrySession();
    expect(resetSession).toHaveBeenCalledOnce();
  });
});

describe("untracedUrl", () => {
  const origin = "http://localhost:5173";
  const absolute = (url: string): string => (url.startsWith("/") ? new URL(url, origin).href : url);

  it.each(["http://localhost:5173/api", "/api/x", "/api?x=1", "/api#f"])(
    "traces the same-origin API URL %s",
    (url) => expect(untracedUrl(origin).test(absolute(url))).toBeFalsy(),
  );

  it.each(["/apix", "/collect", "http://localhost:8080/realms/demo", "http://other.test/api"])(
    "does not trace %s",
    (url) => expect(untracedUrl(origin).test(absolute(url))).toBe(true),
  );
});

describe("reportCaughtError", () => {
  afterEach(() => replaceTelemetryForTesting());

  it("sends the same Error only once", async () => {
    const pushError = vi.fn<NonNullable<Sink["api"]>["pushError"]>();
    replaceTelemetryForTesting({ api: { pushError }, trustedRoutes: [] });
    const error = new Error("caught");
    reportCaughtError(error);
    reportCaughtError(error);
    await vi.waitFor(() => expect(pushError).toHaveBeenCalledOnce());
  });
});
