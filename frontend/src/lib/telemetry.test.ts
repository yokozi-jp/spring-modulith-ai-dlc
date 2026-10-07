import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import {
  replaceTelemetryForTesting,
  reportCaughtError,
  stripUrlQueryAndFragment,
  untracedUrl,
} from "./telemetry";

// SDK の import の制限はテストのファイルにも効くので、型は関数の引数から取る。
type TransportItem = Parameters<typeof stripUrlQueryAndFragment>[0];
type Sink = NonNullable<Parameters<typeof replaceTelemetryForTesting>[0]>;

// SDK の enum を import できないので、送る項目の種類は文字列から型を合わせる。
// oxlint-disable-next-line typescript/no-unsafe-type-assertion
const exceptionType = "exception" as TransportItem["type"];

const exceptionItem = (value: string, filename: string): TransportItem => ({
  type: exceptionType,
  payload: {
    type: "Error",
    value,
    timestamp: "2026-10-06T00:00:00.000Z",
    fatal: false,
    stacktrace: { frames: [{ filename, function: "render", lineno: 10, colno: 5 }] },
  },
  meta: { page: { url: "http://localhost:5173/orders/42?a=1#b" } },
});

describe("stripUrlQueryAndFragment", () => {
  it("removes the query and fragment of the page URL and keeps the path", () => {
    const item = stripUrlQueryAndFragment(exceptionItem("failed", "http://h/a.js"));

    expect(item?.meta.page?.url).toBe("http://localhost:5173/orders/42");
  });

  it("removes the query of a frame filename and keeps lineno and colno", () => {
    const item = stripUrlQueryAndFragment(
      exceptionItem("failed", "http://localhost:5173/assets/index-abc.js?v=1#x"),
    );

    expect(item?.payload).toMatchObject({
      stacktrace: {
        frames: [{ filename: "http://localhost:5173/assets/index-abc.js", lineno: 10, colno: 5 }],
      },
    });
  });

  it("removes the query and fragment of an absolute URL inside the message", () => {
    const item = stripUrlQueryAndFragment(
      exceptionItem("failed to load https://h/api/orders?token=x#f now", "http://h/a.js"),
    );

    expect(item?.payload).toMatchObject({ value: "failed to load https://h/api/orders now" });
  });

  it("removes the query of a URL with an uppercase scheme", () => {
    const item = stripUrlQueryAndFragment(
      exceptionItem("see HTTPS://H/x?q=1 now", "http://h/a.js"),
    );

    expect(item?.payload).toMatchObject({ value: "see HTTPS://H/x now" });
  });

  it("keeps strings without a URL, numbers and booleans", () => {
    const original = { ...exceptionItem("plain a/b?c", "app.js"), meta: {} };

    expect(stripUrlQueryAndFragment(original)).toStrictEqual(original);
  });

  it.each([
    ["/api/x?id=1#f", "/api/x"],
    ["GET /api/x?id=1 failed", "GET /api/x failed"],
    ["url=/api/x?id=1", "url=/api/x"],
    ["a/b?c", "a/b?c"],
  ])("removes the query and fragment of a relative URL in %s", (value, expected) => {
    const item = stripUrlQueryAndFragment(exceptionItem(value, "app.js"));

    expect(item?.payload).toMatchObject({ value: expected });
  });

  it("removes the query and fragment of url.full in a trace item", () => {
    // oxlint-disable-next-line typescript/no-unsafe-type-assertion
    const traceType = "trace" as TransportItem["type"];
    const span = {
      attributes: [
        { key: "url.full", value: { stringValue: "http://localhost:5173/api/x?token=s#f" } },
      ],
    };
    // span の必須の項目をすべて書かずに済むよう、例外の項目の payload を差し替える。
    const trace = { ...exceptionItem("failed", "app.js"), type: traceType, meta: {} };
    Reflect.set(trace, "payload", { resourceSpans: [{ scopeSpans: [{ spans: [span] }] }] });
    const item = stripUrlQueryAndFragment(trace);

    expect(item?.payload).toMatchObject({
      resourceSpans: [
        {
          scopeSpans: [
            {
              spans: [
                {
                  attributes: [
                    { key: "url.full", value: { stringValue: "http://localhost:5173/api/x" } },
                  ],
                },
              ],
            },
          ],
        },
      ],
    });
  });

  it("drops an item that cannot be cloned", () => {
    const item = exceptionItem("see http://h/x?q=1", "http://h/a.js");
    Reflect.set(item.payload, "symbol", Symbol("not cloneable"));

    expect(stripUrlQueryAndFragment(item)).toBeNull();
  });

  it("does not modify the given item", () => {
    const original = exceptionItem("see http://h/x?q=1", "http://h/a.js?v=1");
    const copy = structuredClone(original);

    stripUrlQueryAndFragment(original);

    expect(original).toStrictEqual(copy);
  });
});

describe("untracedUrl", () => {
  const origin = "http://localhost:5173";
  // 計装は URL を絶対 URL にしてから比べるので、相対 URL だけを同じ形にする。
  // http://localhost:5173.evil.test/api は new URL() で解析できないので、そのまま比べる。
  const absolute = (url: string): string => (url.startsWith("/") ? new URL(url, origin).href : url);

  it.each(["http://localhost:5173/api", "/api/x", "/api?x=1", "/api#f"])(
    "traces the same-origin API URL %s",
    (url) => {
      expect(untracedUrl(origin).test(absolute(url))).toBeFalsy();
    },
  );

  it.each([
    "/apix",
    "/collect",
    "/oauth2/authorization/web",
    "/actuator/health/liveness",
    "/v3/api-docs",
    "http://localhost:8080/realms/demo",
    "http://localhost:5173.evil.test/api",
    "http://localhost:51730/api",
    "http://other.test/api",
  ])("does not trace %s", (url) => {
    expect(untracedUrl(origin).test(absolute(url))).toBe(true);
  });

  // OpenTelemetry の isUrlIgnored は test で比べるので、lastIndex を持つ g と y の flag を付けない。
  it("gives the same result when the same RegExp is used twice", () => {
    const pattern = untracedUrl(origin);
    const url = absolute("/collect");

    expect([pattern.test(url), pattern.test(url)]).toStrictEqual([true, true]);
    expect(pattern.flags).toBe("u");
  });
});

describe("reportCaughtError", () => {
  afterEach(() => {
    replaceTelemetryForTesting();
  });

  // TanStack Router は throw された値を変換せずに errorComponent へ渡す。
  // oxlint-disable-next-line unicorn/no-null -- throw null も errorComponent に届く。
  it.each(["thrown string", 404, undefined, null, { message: "plain object" }])(
    "ignores the non-Error value %s",
    (value) => {
      expect(() => {
        reportCaughtError(value);
      }).not.toThrow();
    },
  );

  it("sends the same Error only once", async () => {
    const pushError = vi.fn<Sink["api"]["pushError"]>();
    replaceTelemetryForTesting({ api: { pushError } });
    const error = new Error("caught");

    reportCaughtError(error);
    reportCaughtError(error);

    await vi.waitFor(() => {
      expect(pushError).toHaveBeenCalledOnce();
    });
    expect(pushError).toHaveBeenCalledWith(error);
  });
});
