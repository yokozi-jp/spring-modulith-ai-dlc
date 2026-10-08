import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import {
  bindRouterTelemetry,
  replaceTelemetryForTesting,
  reportCaughtError,
  resetTelemetrySession,
  untracedUrl,
} from "./telemetry";

type Sink = NonNullable<Parameters<typeof replaceTelemetryForTesting>[0]>;
type Router = Parameters<typeof bindRouterTelemetry>[0];

type Listener = Parameters<Router["subscribe"]>[1];

function router(fullPath: string) {
  const listeners: Listener[] = [];
  const source: Router = {
    state: { matches: [{ fullPath }] },
    subscribe: (_event, next) => {
      listeners.push(next);
      return vi.fn<() => void>();
    },
  };
  const resolve = (hrefChanged = true): void => {
    for (const listener of listeners) {
      listener({ hrefChanged });
    }
  };
  return { source, resolve };
}

describe("router telemetry", () => {
  afterEach(() => {
    replaceTelemetryForTesting();
  });

  it("records resolved transitions in order and ignores invalidation", async () => {
    const setView = vi.fn<NonNullable<Sink["api"]["setView"]>>();
    const pushEvent = vi.fn<NonNullable<Sink["api"]["pushEvent"]>>();
    replaceTelemetryForTesting({
      api: { pushError: vi.fn<Sink["api"]["pushError"]>(), setView, pushEvent },
      trustedRoutes: [],
    });
    const fake = router("/");
    expect(bindRouterTelemetry(fake.source)).toBeTypeOf("function");

    fake.resolve();
    fake.resolve(false);
    fake.resolve();

    await vi.waitFor(() => {
      expect(pushEvent).toHaveBeenCalledExactlyOnceWith(
        "view_changed",
        { fromView: "/", toView: "/" },
        undefined,
        { skipDedupe: true },
      );
    });
    expect(setView).toHaveBeenCalledExactlyOnceWith({ name: "/" });
  });

  it("does not send an untrusted route", () => {
    const setView = vi.fn<NonNullable<Sink["api"]["setView"]>>();
    replaceTelemetryForTesting({
      api: { pushError: vi.fn<Sink["api"]["pushError"]>(), setView },
      trustedRoutes: [],
    });
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

  it("removes the stored session, pauses, sets a new short id and resumes", async () => {
    const calls: string[] = [];
    vi.stubGlobal("sessionStorage", {
      removeItem: (key: string) => {
        calls.push(`remove:${key}`);
      },
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
      genShortID: () => "Abc2345678",
      storedSessionKey: "k",
    });

    await resetTelemetrySession();

    expect(calls).toStrictEqual(["remove:k", "pause", "set:Abc2345678", "unpause"]);
  });

  it.each(["genShortID", "setSession"])("stays paused when %s fails", async (failure) => {
    const unpause = vi.fn<() => void>();
    const fail = (name: string): void => {
      if (failure === name) {
        throw new Error(`${name} failed`);
      }
    };
    replaceTelemetryForTesting({
      api: {
        pushError: vi.fn<Sink["api"]["pushError"]>(),
        setSession: () => {
          fail("setSession");
        },
      },
      unpause,
      genShortID: () => {
        fail("genShortID");
        return "Abc2345678";
      },
    });

    await expect(resetTelemetrySession()).resolves.toBeUndefined();
    expect(unpause).not.toHaveBeenCalled();
  });

  it("keeps sending suppressed when unpause fails", async () => {
    const pushError = vi.fn<Sink["api"]["pushError"]>();
    replaceTelemetryForTesting({
      api: { pushError },
      unpause: () => {
        throw new Error("unpause failed");
      },
    });

    await resetTelemetrySession();
    reportCaughtError(new Error("after switch"));
    // 送信は faroReady を待つ 1 つの microtask の後に起きるので、それより後に確かめる。
    await Promise.resolve();

    expect(pushError).not.toHaveBeenCalled();
  });
});

describe("untracedUrl", () => {
  const origin = "http://localhost:5173";
  const absolute = (url: string): string => (url.startsWith("/") ? new URL(url, origin).href : url);

  it.each(["http://localhost:5173/api", "/api/x", "/api?x=1", "/api#f"])(
    "traces the same-origin API URL %s",
    (url) => {
      expect(untracedUrl(origin).test(absolute(url))).toBeFalsy();
    },
  );

  it.each(["/apix", "/collect", "http://localhost:8080/realms/demo", "http://other.test/api"])(
    "does not trace %s",
    (url) => {
      expect(untracedUrl(origin).test(absolute(url))).toBe(true);
    },
  );
});

describe("reportCaughtError", () => {
  afterEach(() => {
    replaceTelemetryForTesting();
  });

  it("sends the same Error only once", async () => {
    const pushError = vi.fn<NonNullable<Sink["api"]>["pushError"]>();
    replaceTelemetryForTesting({ api: { pushError }, trustedRoutes: [] });
    const error = new Error("caught");
    reportCaughtError(error);
    reportCaughtError(error);
    await vi.waitFor(() => {
      expect(pushError).toHaveBeenCalledOnce();
    });
  });
});
