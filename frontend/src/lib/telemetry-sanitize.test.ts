import { afterEach, beforeEach, describe, expect, it, vi } from "vite-plus/test";

import { sanitizeTelemetryItem } from "./telemetry-sanitize";

const origin = "http://localhost:5173";
const root = new Set(["/"]);

// SDK の enum をテストへ import しない。
const item = (type: string, payload: object, meta: object = {}) => ({ type, payload, meta });

const meta = {
  app: { name: "demo-web", namespace: "demo", version: "1.0.0", environment: "local" },
  session: { id: "Abc2345678", attributes: { isSampled: "true", secret: "x" } },
  view: { name: "/" },
  page: { url: "http://localhost:5173/orders/42?a=1#b" },
  user: { email: "person@example.com" },
};

const exceptionItem = (value: string, filename: string) =>
  item(
    "exception",
    {
      type: "Error",
      value,
      timestamp: "2026-10-06T00:00:00.000Z",
      fatal: false,
      stacktrace: { frames: [{ filename, function: "render", lineno: 10, colno: 5 }] },
    },
    meta,
  );

const viewEvent = (attributes: object) =>
  item("event", { name: "view_changed", timestamp: "2026-10-06T00:00:00Z", attributes }, meta);

const measurement = (values: object) =>
  item(
    "measurement",
    { type: "web-vitals", timestamp: "2026-10-06T00:00:00Z", values, context: { secret: "x" } },
    meta,
  );

describe("sanitizeTelemetryItem URLs", () => {
  beforeEach(() => {
    vi.stubGlobal("location", { origin });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
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
    [String.raw`https://host/x?q=1\nnext`, String.raw`[redacted-url]\nnext`],
    ["a/b?c", "a/b?c"],
  ])("replaces the complete URL token in %s", (value, expected) => {
    const sanitized = sanitizeTelemetryItem(exceptionItem(value, value), root);

    expect(sanitized?.payload).toMatchObject({
      value: expected,
      stacktrace: { frames: [{ filename: expected, lineno: 10, colno: 5 }] },
    });
    expect(JSON.stringify(sanitized)).not.toContain("person@example.com");
  });

  it.each([
    [`${origin}/assets/index-abc.js`, "/assets/index-abc.js"],
    ["/assets/vendor.min-1.js", "/assets/vendor.min-1.js"],
    [`${origin}/assets/index-abc.js?v=1`, "[redacted-url]"],
    [`${origin}/assets/index-abc.js#f`, "[redacted-url]"],
    ["https://other.test/assets/index-abc.js", "[redacted-url]"],
    ["/assets/../orders/42.js", "[redacted-url]"],
    ["/orders/42/assets/x.js", "[redacted-url]"],
  ])("keeps only a same-origin build asset path for the frame %s", (filename, expected) => {
    const sanitized = sanitizeTelemetryItem(exceptionItem("failed", filename), root);

    expect(sanitized?.payload).toMatchObject({ stacktrace: { frames: [{ filename: expected }] } });
  });

  it.each([
    [`at r (${origin}/assets/index-abc.js:10:5)`, "at r (/assets/index-abc.js:10:5)"],
    ["at r /assets/index-abc.js:10:5", "at r /assets/index-abc.js:10:5"],
    ["/assets/a.js:1:2 /orders/42", "/assets/a.js:1:2 [redacted-url]"],
    [`${origin}/orders/42?t=/assets/a.js:1:2`, "[redacted-url]"],
    [`at r (${origin}/assets/index-abc.js?v=1:10:5)`, "at r ([redacted-url]"],
    ["at r (https://other.test/assets/index-abc.js:10:5)", "at r ([redacted-url]"],
  ])("keeps a same-origin build asset position in the text %s", (value, expected) => {
    const sanitized = sanitizeTelemetryItem(exceptionItem(value, "x"), root);

    expect(sanitized?.payload).toMatchObject({ value: expected });
  });

  it("redacts every URL when the location is unavailable", () => {
    vi.stubGlobal("location", {});
    const sanitized = sanitizeTelemetryItem(exceptionItem("x", "/assets/index-abc.js"), root);

    expect(sanitized?.payload).toMatchObject({
      stacktrace: { frames: [{ filename: "[redacted-url]" }] },
    });
  });
});

describe("sanitizeTelemetryItem metadata and payloads", () => {
  it("uses only a trusted route template for page metadata", () => {
    expect(sanitizeTelemetryItem(exceptionItem("failed", "app.js"), root)?.meta).toStrictEqual({
      app: { name: "demo-web", namespace: "demo", version: "1.0.0", environment: "local" },
      session: { id: "Abc2345678", attributes: { isSampled: "true" } },
      view: { name: "/" },
      page: { url: "/" },
    });
  });

  it("keeps an initial root view without the unknown source", () => {
    const event = viewEvent({ fromView: "unknown", toView: "/" });

    expect(sanitizeTelemetryItem(event, root)?.payload).toStrictEqual({
      name: "view_changed",
      timestamp: "2026-10-06T00:00:00Z",
      attributes: { toView: "/" },
    });
  });

  it("keeps only trusted route templates in a view transition", () => {
    const event = viewEvent({ fromView: "/", toView: "/logged-out" });

    expect(sanitizeTelemetryItem(event, new Set(["/", "/logged-out"]))?.payload).toStrictEqual({
      name: "view_changed",
      timestamp: "2026-10-06T00:00:00Z",
      attributes: { fromView: "/", toView: "/logged-out" },
    });
  });

  it("drops a view event to an untrusted route", () => {
    expect(sanitizeTelemetryItem(viewEvent({ toView: "/orders/42" }), root)).toBeNull();
  });

  it.each([
    [{ lcp: 120, delta: 3 }, "lcp", 120],
    [{ inp: 18, input_delay: 2 }, "inp", 18],
    [{ cls: 0.12, element: "#secret" }, "cls", 0.12],
  ])("keeps only one Core Web Vital value", (values, name, expected) => {
    expect(sanitizeTelemetryItem(measurement(values), root)?.payload).toStrictEqual({
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
    { lcp: "1" },
    { lcp: Number.NaN },
    { lcp: Number.POSITIVE_INFINITY },
  ])("drops an unsupported Web Vital %j", (values) => {
    expect(sanitizeTelemetryItem(measurement(values), root)).toBeNull();
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
    const spans = [{ ...span, attributes: [span.attributes[3]] }];

    expect(sanitizeTelemetryItem(trace, root)?.payload).toMatchObject({
      resourceSpans: [{ scopeSpans: [{ spans }] }],
    });
  });

  it("drops an item that cannot be cloned", () => {
    const original = exceptionItem("failed", "app.js");
    Reflect.set(original.payload, "symbol", Symbol("not cloneable"));
    expect(sanitizeTelemetryItem(original, root)).toBeNull();
  });
});
