// beforeSend の縮約。SDK に依存せず、telemetry.ts だけが呼ぶ（ADR-068）。
const absoluteUrl = /(?<prefix>^|[^A-Za-z0-9+.-])(?<url>[A-Za-z][A-Za-z0-9+.-]*:[^\s\\]+)/gu;
const relativeUrl = /(?<prefix>^|[^A-Za-z0-9%/._~-])(?<url>\/[^\s\\]+)/gu;
// hash 付きのビルドのファイル名は秘密を含まず、source map で行と列を戻すのに要る。
const assetPath = /^\/assets\/[\w.-]+\.js$/u;
// 行と列の付いたファイル名。直後は ) か空白か末尾に限る（Collector と同じ境界）。
const assetPosition =
  /(?<prefix>^|[^A-Za-z0-9+.-])(?<file>(?:[A-Za-z][A-Za-z0-9+.-]*:|\/)\S*?\.js)(?<position>:\d+:\d+)(?=[)\s]|$)/gu;
const coreWebVitals = ["lcp", "inp", "cls"] as const;

interface TelemetryItemShape {
  type: string;
  payload: unknown;
  meta: unknown;
}

function record(value: unknown): Record<string, unknown> | undefined {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return undefined;
  }
  return Object.fromEntries(Object.entries(value));
}

/** 同一オリジンの /assets/<名前>.js なら path だけを返す。query か fragment があれば返さない。 */
function sameOriginAssetPath(value: string): string | undefined {
  try {
    const { origin } = globalThis.location;
    const url = new URL(value, origin);
    if (
      url.origin === origin &&
      url.search === "" &&
      url.hash === "" &&
      assetPath.test(url.pathname)
    ) {
      return url.pathname;
    }
  } catch {
    // URL として読めない値は縮約する。
  }
  return undefined;
}

function redactUrlTokens(value: string): string {
  return value
    .replaceAll(absoluteUrl, "$<prefix>[redacted-url]")
    .replaceAll(relativeUrl, "$<prefix>[redacted-url]");
}

/** 同一オリジンのビルドのファイル名と行と列だけを path で残し、それ以外の URL token を縮約する。 */
function redactText(value: string): string {
  const parts: string[] = [];
  let rest = 0;
  for (const { index, groups } of value.matchAll(assetPosition)) {
    const { prefix = "", file = "", position = "" } = groups ?? {};
    const path = sameOriginAssetPath(file);
    if (path !== undefined) {
      parts.push(redactUrlTokens(value.slice(rest, index + prefix.length)), path, position);
      rest = index + prefix.length + file.length + position.length;
    }
  }
  parts.push(redactUrlTokens(value.slice(rest)));
  return parts.join("");
}

function redactStrings(value: unknown): void {
  if (typeof value !== "object" || value === null) {
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (typeof child !== "string") {
      redactStrings(child);
    } else if (key === "filename") {
      Reflect.set(value, key, sameOriginAssetPath(child) ?? redactText(child));
    } else {
      Reflect.set(value, key, redactText(child));
    }
  }
}

function withoutUrlAttributes(attributes: unknown[]): unknown[] {
  return attributes.filter((attribute) => {
    const candidate = record(attribute);
    return typeof candidate?.key !== "string" || !candidate.key.startsWith("url.");
  });
}

function removeTraceUrls(value: unknown): void {
  if (typeof value !== "object" || value === null) {
    return;
  }
  if (Array.isArray(value)) {
    for (const child of value) {
      removeTraceUrls(child);
    }
    return;
  }
  for (const [key, child] of Object.entries(value)) {
    if (key === "attributes" && Array.isArray(child)) {
      Reflect.set(value, key, withoutUrlAttributes(child));
    } else {
      removeTraceUrls(child);
    }
  }
}

function normalizedMeta(
  metaValue: unknown,
  trustedRoutes: ReadonlySet<string>,
): Record<string, unknown> {
  const meta = record(metaValue);
  const app = record(meta?.app);
  const sessionId = record(meta?.session)?.id;
  const viewName = record(meta?.view)?.name;
  const appFields = ["name", "namespace", "version", "environment"];
  return {
    ...(app
      ? {
          app: Object.fromEntries(
            appFields.filter((key) => typeof app[key] === "string").map((key) => [key, app[key]]),
          ),
        }
      : {}),
    ...(typeof sessionId === "string"
      ? { session: { id: sessionId, attributes: { isSampled: "true" } } }
      : {}),
    ...(typeof viewName === "string" && trustedRoutes.has(viewName)
      ? { view: { name: viewName }, page: { url: viewName } }
      : {}),
  };
}

function singleCoreWebVital(values: Record<string, unknown>): [string, number] | undefined {
  const present = coreWebVitals.filter((name) => Object.hasOwn(values, name));
  const [name] = present;
  const value: unknown = name === undefined ? undefined : values[name];
  if (present.length !== 1 || name === undefined || typeof value !== "number") {
    return undefined;
  }
  return Number.isFinite(value) && value >= 0 ? [name, value] : undefined;
}

function normalizeMeasurement(payloadValue: unknown): Record<string, unknown> | undefined {
  const payload = record(payloadValue);
  const values = record(payload?.values);
  const vital = payload?.type === "web-vitals" && values ? singleCoreWebVital(values) : undefined;
  if (!vital) {
    return undefined;
  }
  const [name, value] = vital;
  return { type: "web-vitals", timestamp: payload?.timestamp, values: { [name]: value } };
}

function normalizeViewEvent(
  payloadValue: unknown,
  trustedRoutes: ReadonlySet<string>,
): Record<string, unknown> | undefined {
  const payload = record(payloadValue);
  const attributes = record(payload?.attributes);
  const toView = attributes?.toView;
  if (
    payload?.name !== "view_changed" ||
    typeof toView !== "string" ||
    !trustedRoutes.has(toView)
  ) {
    return undefined;
  }
  const normalizedAttributes: Record<string, string> = { toView };
  if (typeof attributes?.fromView === "string" && trustedRoutes.has(attributes.fromView)) {
    normalizedAttributes.fromView = attributes.fromView;
  }
  return { name: "view_changed", timestamp: payload.timestamp, attributes: normalizedAttributes };
}

function normalizedPayload(
  type: string,
  payload: unknown,
  trustedRoutes: ReadonlySet<string>,
): unknown {
  if (type === "measurement") {
    return normalizeMeasurement(payload);
  }
  if (type === "event") {
    return normalizeViewEvent(payload, trustedRoutes);
  }
  redactStrings(payload);
  if (type === "trace") {
    removeTraceUrls(payload);
  }
  return payload;
}

function sanitizeItem<Item extends TelemetryItemShape>(
  item: Item,
  trustedRoutes: ReadonlySet<string>,
): Item | undefined {
  try {
    const copy = structuredClone(item);
    const payload = normalizedPayload(copy.type, copy.payload, trustedRoutes);
    if (payload === undefined) {
      return undefined;
    }
    Reflect.set(copy, "payload", payload);
    Reflect.set(copy, "meta", normalizedMeta(copy.meta, trustedRoutes));
    return copy;
  } catch {
    return undefined;
  }
}

/** beforeSend で URL、付加情報、未検証の値を縮約する。trustedRoutes は Router が解決した route template。 */
export function sanitizeTelemetryItem<Item extends TelemetryItemShape>(
  item: Item,
  trustedRoutes: ReadonlySet<string>,
): Item | null {
  // oxlint-disable-next-line unicorn/no-null -- Faro の beforeSend の契約が null で項目を捨てる。
  return sanitizeItem(item, trustedRoutes) ?? null;
}
