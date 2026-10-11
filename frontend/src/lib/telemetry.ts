import type { Faro } from "@grafana/faro-web-sdk";

import { sanitizeTelemetryItem } from "./telemetry-sanitize";

// vite.config.ts の define がビルド時に置き換える（ADR-068）。
declare const __TELEMETRY_ENABLED__: boolean;
declare const __TELEMETRY_APP__: {
  name: string;
  namespace: string;
  version: string;
  environment: string;
};

interface RouterTelemetrySource {
  readonly state: { readonly matches: readonly { readonly fullPath: string }[] };
  subscribe: (
    eventType: "onResolved",
    listener: (event: { readonly hrefChanged: boolean }) => void,
  ) => () => void;
}

type TelemetryApi = Pick<Faro["api"], "pushError" | "pushEvent" | "setSession" | "setView">;
interface TelemetrySink {
  api: TelemetryApi;
  pause: () => void;
  unpause: () => void;
  genShortID: () => string;
}

interface TelemetryTestSink {
  api: Pick<TelemetryApi, "pushError"> & Partial<Omit<TelemetryApi, "pushError">>;
  pause?: () => void;
  unpause?: () => void;
  genShortID?: () => string;
  storedSessionKey?: string;
  trustedRoutes?: readonly string[];
}

// oxlint-disable-next-line eslint/init-declarations -- 無効のビルドでは代入せず、undefined のままにする。
let faroReady: Promise<TelemetrySink | undefined> | undefined;
let telemetryEnabled = false;
let sendingSuppressed = false;
// route template は / で始まるので、空文字は未送信を表す。
let currentView = "";
// 有効のビルドだけが SDK の session の保存先を持つ。
let storedSessionKey = "";
const trustedRoutes = new Set<string>();
const reported = new WeakSet<Error>();

function noop(): void {
  // テストが使わない SDK method を補う。
}

export function untracedUrl(origin: string): RegExp {
  const escaped = origin.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
  // origin は location.origin で利用者が制御できず、メタ文字をすべてエスケープした固定文字列の否定先読みなので ReDoS は起きない。
  // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp
  return new RegExp(`^(?!${escaped}/api(?:[/?#]|$))`, "u");
}

function validRouteTemplate(value: string | undefined): value is string {
  return (
    value !== undefined && value.startsWith("/") && !value.includes("?") && !value.includes("#")
  );
}

function processRoute(template: string, sink: TelemetrySink | undefined): void {
  if (!sink || sendingSuppressed) {
    return;
  }
  trustedRoutes.add(template);
  try {
    if (currentView === template) {
      sink.api.pushEvent("view_changed", { fromView: template, toView: template }, undefined, {
        skipDedupe: true,
      });
    } else {
      sink.api.setView({ name: template });
      currentView = template;
    }
  } catch {
    // テレメトリの失敗で画面遷移を止めない。
  }
}

/** TanStack Router が解決した route template を View として送る。 */
export function bindRouterTelemetry(router: RouterTelemetrySource): () => void {
  return router.subscribe("onResolved", ({ hrefChanged }) => {
    if (!hrefChanged || !telemetryEnabled || sendingSuppressed) {
      return;
    }
    const template = router.state.matches.at(-1)?.fullPath;
    if (!validRouteTemplate(template)) {
      return;
    }
    void (async () => {
      const sink = await faroReady;
      processRoute(template, sink);
    })();
  });
}

function testTelemetrySink(sink: TelemetryTestSink): TelemetrySink {
  return {
    api: {
      pushError: sink.api.pushError,
      pushEvent: sink.api.pushEvent ?? noop,
      setSession: sink.api.setSession ?? noop,
      setView: sink.api.setView ?? noop,
    },
    pause: sink.pause ?? noop,
    unpause: sink.unpause ?? noop,
    genShortID: sink.genShortID ?? (() => "Abc2345678"),
  };
}

/** テストから SDK 境界の送り先を差し替える。 */
export function replaceTelemetryForTesting(sink?: TelemetryTestSink): void {
  telemetryEnabled = sink !== undefined;
  sendingSuppressed = false;
  currentView = "";
  storedSessionKey = sink?.storedSessionKey ?? "";
  trustedRoutes.clear();
  for (const route of sink?.trustedRoutes ?? []) {
    trustedRoutes.add(route);
  }
  faroReady = sink ? Promise.resolve(testTelemetrySink(sink)) : undefined;
}

/** composition root から一度だけ呼ぶ。SDK の読み込みを待たない。 */
export function initTelemetry(): void {
  if (__TELEMETRY_ENABLED__) {
    telemetryEnabled = true;
    // ponytail: SDK の STORAGE_KEY と同じ値。SDK が 500 ms 以内に読み込まれなくても消せるよう直書きし、切り替えは e2e で確かめる。
    storedSessionKey = "com.grafana.faro.session";
    faroReady = (async (): Promise<TelemetrySink | undefined> => {
      try {
        const [
          {
            ErrorsInstrumentation,
            FetchTransport,
            InternalLoggerLevel,
            SessionInstrumentation,
            ViewInstrumentation,
            WebVitalsInstrumentation,
            genShortID,
            initializeFaro,
          },
          { TracingInstrumentation },
        ] = await Promise.all([
          import("@grafana/faro-web-sdk"),
          import("@grafana/faro-web-tracing"),
        ]);
        const faro = initializeFaro({
          app: __TELEMETRY_APP__,
          batching: { enabled: false },
          sessionTracking: { enabled: true, persistent: false, samplingRate: 1 },
          webVitalsInstrumentation: { reportAllChanges: false, trackAttributionSources: false },
          instrumentations: [
            new SessionInstrumentation(),
            new ViewInstrumentation(),
            new ErrorsInstrumentation(),
            new WebVitalsInstrumentation(),
            new TracingInstrumentation(),
          ],
          ignoreUrls: [untracedUrl(globalThis.location.origin)],
          transports: [
            new FetchTransport({ url: "/collect", requestOptions: { credentials: "omit" } }),
          ],
          beforeSend: (item) => sanitizeTelemetryItem(item, trustedRoutes),
          internalLoggerLevel: InternalLoggerLevel.OFF,
        });
        return {
          api: faro.api,
          pause: () => {
            faro.pause();
          },
          unpause: () => {
            faro.unpause();
          },
          genShortID,
        };
      } catch {
        return undefined;
      }
    })();
  }
}

export function reportCaughtError(error: unknown): void {
  if (!(error instanceof Error) || reported.has(error) || sendingSuppressed) {
    return;
  }
  reported.add(error);
  void (async () => {
    const faro = await faroReady;
    faro?.api.pushError(error);
  })();
}

function removeStoredSession(): void {
  if (storedSessionKey === "") {
    return;
  }
  try {
    sessionStorage.removeItem(storedSessionKey);
  } catch {
    // storage が使えなくても logout を続ける。
  }
}

function sinkWithin(milliseconds: number): Promise<TelemetrySink | undefined> {
  return Promise.race([
    faroReady,
    // oxlint-disable-next-line promise/avoid-new -- setTimeout を待つ標準の Promise がない。
    new Promise<undefined>((resolve) => {
      setTimeout(resolve, milliseconds);
    }),
  ]);
}

function switchSession(sink: TelemetrySink): void {
  try {
    sink.pause();
    sendingSuppressed = true;
  } catch {
    sendingSuppressed = true;
    return;
  }
  try {
    sink.api.setSession({ id: sink.genShortID() });
    sink.unpause();
    sendingSuppressed = false;
  } catch {
    // 旧 session へ新しい signal を加えないため pause のままにする。
  }
}

/** logout の送信前に匿名の Faro session を切り替える。 */
export async function resetTelemetrySession(): Promise<void> {
  removeStoredSession();
  const sink = await sinkWithin(500);
  if (sink && !sendingSuppressed) {
    switchSession(sink);
  }
}
