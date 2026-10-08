import type { Faro, TransportItem } from "@grafana/faro-web-sdk";

// vite.config.ts の define がビルド時に置き換える（ADR-068）。
declare const __TELEMETRY_ENABLED__: boolean;
declare const __TELEMETRY_APP__: {
  name: string;
  namespace: string;
  version: string;
  environment: string;
};

// 対象は http と https の絶対 URL（scheme の大文字と小文字は区別しない）と、/ で始まる相対 URL である。
// 相対 URL とみなすのは、文字列の先頭、空白、(、"、'、= の直後の / からで、a/b?c のような語の途中は対象にしない。
// 文の中の URL も消すので、new URL() による構文解析ではなく正規表現にする。
// ponytail: : の直後の相対 URL の query は消さない（error:/api/x?id=1 など）。
// ponytail: query は空白までとみなすので、(see http://h/a?x=1) の閉じ括弧も消える。秘密を残すより表示の崩れを取る。
const urlQueryOrFragment = /(?<url>(?:https?:\/\/|(?<=^|[\s("'=])\/)[^\s?#]*)[?#]\S*/giu;

// as による型の表明を避けるため、structuredClone で複製した項目をその場で書き換える。
function stripStrings(value: unknown): void {
  if (typeof value !== "object" || value === null) {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (typeof child === "string") {
      Reflect.set(value, key, child.replaceAll(urlQueryOrFragment, "$<url>"));
    } else {
      stripStrings(child);
    }
  }
}

/** 送る項目のすべての文字列から、絶対 URL と / で始まる相対 URL の query と fragment を消す。消せない項目は捨てる。 */
export function stripUrlQueryAndFragment(item: TransportItem): TransportItem | null {
  try {
    const copy = structuredClone(item);
    stripStrings(copy);
    return copy;
  } catch {
    // 複製できない値や循環を含む項目は、query を残したまま送らないよう捨てる。
    // Faro は beforeSend の例外を捕まえず、batch の buffer に戻して送信が止まる（faro-core の BatchExecutor.flush）。
    // oxlint-disable-next-line unicorn/no-null -- Faro の BeforeSendHook は null で項目を捨てる。
    return null;
  }
}

/**
 * 同一オリジンの /api と /api/**（query と fragment を含む）以外の URL に一致する正規表現を返す。
 * Faro の ignoreUrls に渡し、traceparent と span を同一オリジンの /api/** の要求だけにする。
 */
export function untracedUrl(origin: string): RegExp {
  const escaped = origin.replaceAll(/[.*+?^${}()|[\]\\]/gu, String.raw`\$&`);
  // flag は u だけにする。OpenTelemetry の isUrlIgnored は test で比べ、g と y は lastIndex を持ち越すため。
  return new RegExp(`^(?!${escaped}/api(?:[/?#]|$))`, "u");
}

// reportCaughtError が使う Faro の部分。テストは pushError だけを持つ値を渡す。
interface ErrorSink {
  api: Pick<Faro["api"], "pushError">;
}

// oxlint-disable-next-line eslint/init-declarations -- 無効のビルドでは代入せず、undefined のままにする。
let faroReady: Promise<ErrorSink | undefined> | undefined;
const reported = new WeakSet<Error>();

/** テストから送り先を差し替える。アプリケーションのコードからは呼ばない。 */
export function replaceTelemetryForTesting(sink?: ErrorSink): void {
  faroReady = Promise.resolve(sink);
}

/** composition root から一度だけ呼ぶ。SDK の読み込みを待たない。 */
export function initTelemetry(): void {
  // import() はこの分岐の中に直接書く。定数の false で分岐ごと消え、SDK の chunk が出力されない（ADR-068）。
  // 早期 return の後に import() を置く形にしない。到達しない文の除去は bundler に依存する。
  if (__TELEMETRY_ENABLED__) {
    faroReady = (async (): Promise<Faro | undefined> => {
      try {
        const [
          { ErrorsInstrumentation, FetchTransport, InternalLoggerLevel, initializeFaro },
          { TracingInstrumentation },
        ] = await Promise.all([
          import("@grafana/faro-web-sdk"),
          import("@grafana/faro-web-tracing"),
        ]);
        return initializeFaro({
          app: __TELEMETRY_APP__,
          // getWebInstrumentations() は使わない。
          // Errors（window.onerror と unhandledrejection）と、fetch と XHR の Tracing だけにする。
          instrumentations: [new ErrorsInstrumentation(), new TracingInstrumentation()],
          // この値は Faro の全計装の除外にも効く。
          // Performance や UserAction を有効にするときは、Tracing の fetchInstrumentationOptions と xhrInstrumentationOptions に移す。
          ignoreUrls: [untracedUrl(globalThis.location.origin)],
          // ponytail: web-tracing の sampler が session の isSampled の属性を読む実装に依存する。
          // SessionInstrumentation がないと sampled flag が 0 の traceparent が送られるので、初期値で sampled にする。
          // #152 で SessionInstrumentation を入れたら、この初期値を消す。
          sessionTracking: { session: { attributes: { isSampled: "true" } } },
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
