/* oxlint-disable eslint/max-statements, eslint/max-lines, eslint/prefer-named-capture-group, typescript/method-signature-style, eslint/curly, unicorn/no-null, unicorn/no-useless-undefined, promise/avoid-new, eslint/no-promise-executor-return, typescript/strict-void-return -- telemetry の信頼境界を一ファイルに閉じ、SDK の beforeSend 契約に null を返す。 */
import type { Faro, TransportItem } from "@grafana/faro-web-sdk";

declare const __TELEMETRY_ENABLED__: boolean;
declare const __TELEMETRY_APP__: {
  name: string;
  namespace: string;
  version: string;
  environment: string;
};

const sessionStorageKey = globalThis.atob("Y29tLmdyYWZhbmEuZmFyby5zZXNzaW9u");
const sessionAlphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ0123456789";
const absoluteUrl = /(^|[^A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]*:[^\s\\]+)/gu;
const relativeUrl = /(^|[^A-Za-z0-9%/._~-])(\/[^\s\\]+)/gu;
const coreWebVitals = ["lcp", "inp", "cls"] as const;

interface RouterTelemetrySource {
  readonly state: { readonly matches: readonly { readonly fullPath: string }[] };
  subscribe(
    eventType: "onResolved",
    listener: (event: { readonly hrefChanged: boolean }) => void,
  ): () => void;
}

type TelemetryApi = Pick<Faro["api"], "pushError" | "pushEvent" | "setSession" | "setView">;
interface TelemetrySink {
  api: TelemetryApi;
  pause: Faro["pause"];
  unpause: Faro["unpause"];
}

interface TelemetryTestSink {
  api: Pick<TelemetryApi, "pushError"> & Partial<Omit<TelemetryApi, "pushError">>;
  pause?: () => void;
  unpause?: () => void;
  resetSession?: () => Promise<void>;
  trustedRoutes?: readonly string[];
}

let faroReady: Promise<TelemetrySink | undefined> | undefined = Promise.resolve<
  TelemetrySink | undefined
>(undefined);
let testSink: TelemetryTestSink | undefined = undefined;
let telemetryEnabled = false;
let sendingSuppressed = false;
let currentView: string | undefined = undefined;
const trustedRoutes = new Set<string>();
const reported = new WeakSet<Error>();

function noop(): void {
  // テストが使わない SDK method を補う。
}

function record(value: unknown): Record<string, unknown> | undefined {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return undefined;
  return Object.fromEntries(Object.entries(value));
}

function redactUrlTokens(value: string): string {
  return value
    .replaceAll(absoluteUrl, "$1[redacted-url]")
    .replaceAll(relativeUrl, "$1[redacted-url]");
}

function redactStrings(value: unknown): void {
  if (typeof value !== "object" || value === null) return;
  for (const [key, child] of Object.entries(value)) {
    if (typeof child === "string") Reflect.set(value, key, redactUrlTokens(child));
    else redactStrings(child);
  }
}

function removeTraceUrls(value: unknown): void {
  if (typeof value !== "object" || value === null) return;
  if (Array.isArray(value)) {
    for (const child of value) removeTraceUrls(child);
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (key === "attributes" && Array.isArray(child)) {
      Reflect.set(
        value,
        key,
        child.filter((attribute) => {
          const candidate = record(attribute);
          return typeof candidate?.key !== "string" || !candidate.key.startsWith("url.");
        }),
      );
    } else {
      removeTraceUrls(child);
    }
  }
}

function normalizedMeta(metaValue: unknown): Record<string, unknown> {
  const meta = record(metaValue);
  const app = record(meta?.app);
  const session = record(meta?.session);
  const view = record(meta?.view);
  const normalized: Record<string, unknown> = {};

  if (app) {
    normalized.app = Object.fromEntries(
      ["name", "namespace", "version", "environment"]
        .filter((key) => typeof app[key] === "string")
        .map((key) => [key, app[key]]),
    );
  }
  if (typeof session?.id === "string") {
    normalized.session = { id: session.id, attributes: { isSampled: "true" } };
  }
  if (typeof view?.name === "string" && trustedRoutes.has(view.name)) {
    normalized.view = { name: view.name };
    normalized.page = { url: view.name };
  }
  return normalized;
}

function normalizeMeasurement(payloadValue: unknown): Record<string, unknown> | undefined {
  const payload = record(payloadValue);
  const values = record(payload?.values);
  if (payload?.type !== "web-vitals" || !values) return undefined;
  const present = coreWebVitals.filter((name) => Object.hasOwn(values, name));
  if (present.length !== 1) return undefined;
  const [name] = present;
  if (!name) return undefined;
  const value: unknown = values[name];
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) return undefined;
  return { type: "web-vitals", timestamp: payload.timestamp, values: { [name]: value } };
}

function normalizeViewEvent(payloadValue: unknown): Record<string, unknown> | undefined {
  const payload = record(payloadValue);
  const attributes = record(payload?.attributes);
  if (payload?.name !== "view_changed" || typeof attributes?.toView !== "string") return undefined;
  if (!trustedRoutes.has(attributes.toView)) return undefined;
  const normalizedAttributes: Record<string, string> = { toView: attributes.toView };
  if (typeof attributes.fromView === "string" && trustedRoutes.has(attributes.fromView)) {
    normalizedAttributes.fromView = attributes.fromView;
  }
  return { name: "view_changed", timestamp: payload.timestamp, attributes: normalizedAttributes };
}

/** beforeSend で URL、付加情報、未検証の値を縮約する。 */
export function sanitizeTelemetryItem(item: TransportItem): TransportItem | null {
  try {
    const copy = structuredClone(item);
    redactStrings(copy.payload);
    const type: string = copy.type;
    if (type === "trace") removeTraceUrls(copy.payload);
    if (type === "measurement") {
      const payload = normalizeMeasurement(copy.payload);
      if (!payload) return null;
      Reflect.set(copy, "payload", payload);
    }
    if (type === "event") {
      const payload = normalizeViewEvent(copy.payload);
      if (!payload) return null;
      Reflect.set(copy, "payload", payload);
    }
    Reflect.set(copy, "meta", normalizedMeta(copy.meta));
    return copy;
  } catch {
    return null;
  }
}

export function untracedUrl(origin: string): RegExp {
  const escaped = origin.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
  return new RegExp(`^(?!${escaped}/api(?:[/?#]|$))`, "u");
}

function validRouteTemplate(value: string | undefined): value is string {
  return (
    value !== undefined && value.startsWith("/") && !value.includes("?") && !value.includes("#")
  );
}

function processRoute(template: string, sink: TelemetrySink | undefined): void {
  if (!sink || sendingSuppressed) return;
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
    if (!hrefChanged || !telemetryEnabled || sendingSuppressed) return;
    const template = router.state.matches.at(-1)?.fullPath;
    if (!validRouteTemplate(template)) return;
    void (async () => {
      const sink = await faroReady;
      processRoute(template, sink);
    })();
  });
}

/** テストから SDK 境界の送り先を差し替える。 */
export function replaceTelemetryForTesting(sink?: TelemetryTestSink): void {
  testSink = sink;
  telemetryEnabled = sink !== undefined;
  sendingSuppressed = false;
  currentView = undefined;
  trustedRoutes.clear();
  for (const route of sink?.trustedRoutes ?? []) trustedRoutes.add(route);
  if (!sink) {
    faroReady = Promise.resolve(undefined);
    return;
  }
  const api: TelemetryApi = {
    pushError: sink.api.pushError,
    pushEvent: sink.api.pushEvent ?? noop,
    setSession: sink.api.setSession ?? noop,
    setView: sink.api.setView ?? noop,
  };
  faroReady = Promise.resolve({
    api,
    pause: sink.pause ?? noop,
    unpause: sink.unpause ?? noop,
  });
}

/** composition root から一度だけ呼ぶ。SDK の読み込みを待たない。 */
export function initTelemetry(): void {
  if (__TELEMETRY_ENABLED__) {
    telemetryEnabled = true;
    faroReady = (async (): Promise<Faro | undefined> => {
      try {
        const [
          {
            ErrorsInstrumentation,
            FetchTransport,
            InternalLoggerLevel,
            SessionInstrumentation,
            ViewInstrumentation,
            WebVitalsInstrumentation,
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
          beforeSend: sanitizeTelemetryItem,
          internalLoggerLevel: InternalLoggerLevel.OFF,
        });
        return faro;
      } catch {
        return undefined;
      }
    })();
  }
}

export function reportCaughtError(error: unknown): void {
  if (!(error instanceof Error) || reported.has(error) || sendingSuppressed) return;
  reported.add(error);
  void (async () => {
    const faro = await faroReady;
    faro?.api.pushError(error);
  })();
}

function randomSessionId(): string {
  const maximum = Math.floor(256 / sessionAlphabet.length) * sessionAlphabet.length;
  let id = "";
  while (id.length < 10) {
    const bytes = crypto.getRandomValues(new Uint8Array(10 - id.length));
    for (const byte of bytes) {
      if (byte < maximum) id += sessionAlphabet[byte % sessionAlphabet.length];
    }
  }
  return id;
}

/** logout の送信前に匿名の Faro session を切り替える。 */
export async function resetTelemetrySession(): Promise<void> {
  try {
    sessionStorage.removeItem(sessionStorageKey);
  } catch {
    // storage が使えなくても logout を続ける。
  }
  if (testSink?.resetSession) {
    await testSink.resetSession();
    return;
  }
  const sink = await Promise.race([
    faroReady,
    new Promise<undefined>((resolve) => setTimeout(resolve, 500)),
  ]);
  if (!sink || sendingSuppressed) return;
  try {
    sink.pause();
    sendingSuppressed = true;
  } catch {
    sendingSuppressed = true;
    return;
  }
  try {
    sink.api.setSession({ id: randomSessionId() });
    sendingSuppressed = false;
    sink.unpause();
  } catch {
    // 旧 session へ新しい signal を加えないため pause のままにする。
  }
}
