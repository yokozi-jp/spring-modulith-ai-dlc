import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import {
  bindRouterTelemetry,
  replaceTelemetryForTesting,
  reportCaughtError,
  resetTelemetrySession,
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
  function resolve(hrefChanged = true): void {
    for (const listener of listeners) {
      listener({ hrefChanged });
    }
  }
  return { source, resolve };
}

describe("画面遷移のテレメトリ", () => {
  afterEach(() => {
    replaceTelemetryForTesting();
  });

  it("解決した遷移を順に記録し、URL が変わらない再解決（invalidate）は無視する", async () => {
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

    await expect
      .poll(() => pushEvent)
      .toHaveBeenCalledExactlyOnceWith("view_changed", { fromView: "/", toView: "/" }, undefined, {
        skipDedupe: true,
      });
    expect(setView).toHaveBeenCalledExactlyOnceWith({ name: "/" });
  });

  it("信頼できない route は送らない", () => {
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

  it("保存したセッションを消して一時停止し、新しい短い ID を設定してから再開する", async () => {
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

  it.each(["genShortID", "setSession"])(
    "%s が失敗すると一時停止したままにする",
    async (failure) => {
      const unpause = vi.fn<() => void>();
      function fail(name: string): void {
        if (failure === name) {
          throw new Error(`${name} failed`);
        }
      }
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
    },
  );

  it.each(["pause", "unpause"] as const)(
    "%s が失敗しても送信を止めたままにする",
    async (failure) => {
      const setSession = vi.fn<NonNullable<Sink["api"]["setSession"]>>();
      const pushError = vi.fn<Sink["api"]["pushError"]>();
      function fail(): never {
        throw new Error(`${failure} failed`);
      }
      replaceTelemetryForTesting({
        api: { pushError, setSession },
        ...(failure === "pause" ? { pause: fail } : { unpause: fail }),
      });

      await resetTelemetrySession();
      reportCaughtError(new Error("after switch"));
      // 送信は faroReady を待つ 1 つの microtask の後に起きるので、それより後に確かめる。
      await Promise.resolve();

      expect(pushError).not.toHaveBeenCalled();
      expect(setSession).toHaveBeenCalledTimes(failure === "pause" ? 0 : 1);
    },
  );
});

describe("reportCaughtError", () => {
  afterEach(() => {
    replaceTelemetryForTesting();
  });

  it("同じ Error は 1 回だけ送る", async () => {
    const pushError = vi.fn<NonNullable<Sink["api"]>["pushError"]>();
    replaceTelemetryForTesting({ api: { pushError }, trustedRoutes: [] });
    const error = new Error("caught");
    reportCaughtError(error);
    reportCaughtError(error);
    await expect.poll(() => pushError).toHaveBeenCalledOnce();
  });
});
