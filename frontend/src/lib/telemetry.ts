import type { Faro, TransportItem } from "@grafana/faro-web-sdk";

// vite.config.ts の define がビルド時に置き換える（ADR-066）。
declare const __TELEMETRY_ENABLED__: boolean;
declare const __TELEMETRY_APP__: {
  name: string;
  namespace: string;
  version: string;
  environment: string;
};

// ponytail: http と https の絶対 URL だけを対象にする。文中の相対 URL（/api/x?id=1）は消さない。
// 相対 URL を送る計装（View、Tracing）を足すときは、ここを URL の構文解析に替える。
const absoluteUrlQueryOrFragment = /(?<url>https?:\/\/[^\s?#]*)[?#]\S*/gu;

// as による型の表明を避けるため、structuredClone で複製した項目をその場で書き換える。
function stripStrings(value: unknown): void {
  if (typeof value !== "object" || value === null) {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (typeof child === "string") {
      Reflect.set(value, key, child.replaceAll(absoluteUrlQueryOrFragment, "$<url>"));
    } else {
      stripStrings(child);
    }
  }
}

/** 送る項目のすべての文字列から、絶対 URL の query と fragment を消す。 */
export function stripUrlQueryAndFragment(item: TransportItem): TransportItem {
  const copy = structuredClone(item);
  stripStrings(copy);
  return copy;
}

// oxlint-disable-next-line eslint/init-declarations -- 無効のビルドでは代入せず、undefined のままにする。
let faroReady: Promise<Faro | undefined> | undefined;
const reported = new WeakSet<Error>();

/** composition root から一度だけ呼ぶ。SDK の読み込みを待たない。 */
export function initTelemetry(): void {
  // import() はこの分岐の中に直接書く。定数の false で分岐ごと消え、SDK の chunk が出力されない（ADR-066）。
  // 早期 return の後に import() を置く形にしない。到達しない文の除去は bundler に依存する。
  if (__TELEMETRY_ENABLED__) {
    faroReady = (async (): Promise<Faro | undefined> => {
      try {
        const { ErrorsInstrumentation, FetchTransport, InternalLoggerLevel, initializeFaro } =
          await import("@grafana/faro-web-sdk");
        return initializeFaro({
          app: __TELEMETRY_APP__,
          // getWebInstrumentations() は使わない。Errors（window.onerror と unhandledrejection）だけにする。
          instrumentations: [new ErrorsInstrumentation()],
          // Cookie を Collector へ送らない。
          transports: [
            new FetchTransport({ url: "/collect", requestOptions: { credentials: "omit" } }),
          ],
          beforeSend: stripUrlQueryAndFragment,
          // 送信の失敗を console に出さない（docs/observability/log-output-points.md）。
          internalLoggerLevel: InternalLoggerLevel.OFF,
        });
      } catch {
        // SDK の読み込みと初期化の失敗で画面を止めない。
        return undefined;
      }
    })();
  }
}

/**
 * route のエラー表示で捕捉したエラーを送る。同じ Error は一度だけ送る。
 * DOM の組み込みの reportError と取り違えないため、この名前にする。
 */
export function reportCaughtError(error: unknown): void {
  // TanStack Router の CatchBoundary は throw された値を変換せずに渡す。
  // Error でない値は WeakSet に入らず、Faro の pushError も Error を前提にするため送らない。
  if (!(error instanceof Error) || reported.has(error)) {
    return;
  }
  reported.add(error);
  void (async () => {
    const faro = await faroReady;
    faro?.api.pushError(error);
  })();
}
