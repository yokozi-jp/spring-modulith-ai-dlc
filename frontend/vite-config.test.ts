// oxlint-disable max-lines -- Vite の設定の契約（port、proxy、CSP、テレメトリ）を 1 つの設定ファイルの test に集めるため、行数で分割しない。
// oxlint-disable-next-line import/no-nodejs-modules -- Vite の proxy の hop を通すため、listen と応答を event で待つ。
import { once } from "node:events";
// oxlint-disable-next-line import/no-nodejs-modules -- 設定が読む version.txt と ZAP の設定を、テストでも同じ場所から読んで比べる。
import { readFileSync } from "node:fs";
// oxlint-disable-next-line import/no-nodejs-modules -- Vite の proxy の hop を通すため、偽の受け口と request を node:http で作る（fetch は lint で禁止）。
import { createServer as createHttpServer, request } from "node:http";
// oxlint-disable-next-line import/no-nodejs-modules -- 偽の受け口が受けた header の型。
import type { IncomingHttpHeaders } from "node:http";
// oxlint-disable-next-line import/no-nodejs-modules -- listen した server のポートを型で取り出す。
import type { AddressInfo } from "node:net";

import { http, passthrough } from "msw";
import { createServer, resolveConfig } from "vite-plus";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

import { server as mswServer } from "./src/testing/msw";

const proxyPath = "^/(api|oauth2|login|logout|error|actuator|v3/api-docs|swagger-ui)(/|$)";

const cspReportingEndpoints = 'csp-endpoint="/csp-report"';

const lastDirective = (contentSecurityPolicy: unknown) =>
  String(contentSecurityPolicy).split(";").at(-1)?.trim();

const credentialHeaders = new Set(["cookie", "authorization", "proxy-authorization"]);

const tcpAddressOf = (address: AddressInfo | string | null | undefined) => {
  if (address === null || address === undefined || typeof address === "string") {
    throw new TypeError("Server is not listening on a TCP port");
  }
  return address;
};

// 偽の Collector。受けた request の path と header を記録して 200 を返し、Vite の転送先をこの port にする。
// proxy の転送先は http://localhost:<port> なので、同じ名前で解決した address で待ち受ける。
const startCollector = async () => {
  const received: { path?: string; headers?: IncomingHttpHeaders } = {};
  const collector = createHttpServer((req, res) => {
    received.path = req.url;
    received.headers = req.headers;
    res.end();
  });
  collector.listen(0, "localhost");
  await once(collector, "listening");
  const port = String(tcpAddressOf(collector.address()).port);
  vi.stubEnv("OTEL_CSP_REPORT_HTTP_PORT", port);
  vi.stubEnv("OTEL_FARO_HTTP_PORT", port);
  vi.stubEnv("FRONTEND_OTEL_ENABLED", "false");
  // 共有の MSW は未登録の request を失敗させるので、この hop だけは実際の通信に通す。
  mswServer.use(http.all("*", () => passthrough()));
  return { collector, received };
};

// ブラウザの Reporting API と同じく、Cookie などの資格情報を付けて送る。
// Vite は localhost で待ち受け、OS によって ::1 と 127.0.0.1 のどちらかに束ねるので、実際に束ねた address へ送る。
const postWithCredentials = ({ address, port }: AddressInfo, path: string) =>
  // oxlint-disable-next-line promise/avoid-new -- events.once は応答を any で返すので、型の付いた callback を Promise で待つ。
  new Promise<number | undefined>((resolve, reject) => {
    const req = request(
      {
        host: address,
        port,
        path,
        method: "POST",
        headers: {
          Cookie: "APP_SESSION=secret",
          Authorization: "Bearer secret",
          "Proxy-Authorization": "Basic secret",
          "Content-Type": "application/reports+json",
        },
      },
      (response) => {
        response.resume();
        resolve(response.statusCode);
      },
    );
    req.on("error", reject);
    req.end("[]");
  });

const createDevelopmentServer = () =>
  createServer({ mode: "development", server: { middlewareMode: true } });

const enableTelemetry = () => {
  vi.stubEnv("FRONTEND_OTEL_ENABLED", "true");
  vi.stubEnv("FRONTEND_OTEL_SERVICE_NAME", "demo-web");
  vi.stubEnv("OTEL_SERVICE_NAMESPACE", "demo");
  vi.stubEnv("OTEL_DEPLOYMENT_ENVIRONMENT_NAME", "test");
};

// 実際に Vite の設定を読み込み開発サーバーを生成するため、所要時間は CPU の空きに比例する。
// pre-push では backend のテストやイメージビルドと並列に走るので、既定の 5 秒では足りないことがある。
// ponytail: タイムアウトは性能の検証ではなくハング検出のためなので、負荷時の実測（5 秒超）に余裕を持たせた 60 秒にする。
describe("Vite configuration", { timeout: 60_000 }, () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("pins the development origin and uses SERVER_PORT for the proxy", async () => {
    vi.stubEnv("SERVER_PORT", "19090");
    const server = await createDevelopmentServer();

    try {
      expect(server.config.server.port).toBe(5173);
      expect(server.config.server.strictPort).toBe(true);
      const { proxy } = server.config.server;
      if (!proxy) {
        throw new Error("Development proxy not configured");
      }
      expect(proxy[proxyPath]).toMatchObject({
        target: "http://localhost:19090",
        changeOrigin: false,
      });
    } finally {
      await server.close();
    }
  });

  it("uses the HTML nonce for the React Refresh preamble and CSP header", async () => {
    const server = await createDevelopmentServer();

    try {
      const nonce = server.config.html?.cspNonce;
      if (typeof nonce !== "string" || nonce === "") {
        throw new Error("Development CSP nonce not configured");
      }
      expect(nonce).toMatch(/^[0-9a-f]{32}$/u);
      expect(server.config.server.headers?.["Content-Security-Policy"]).toContain(
        `script-src 'self' 'nonce-${nonce}'`,
      );

      const html = await server.transformIndexHtml(
        "/",
        '<!doctype html><html><head></head><body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body></html>',
      );
      expect(html).toContain(
        `<script type="module" nonce="${nonce}">import { injectIntoGlobalHook }`,
      );
    } finally {
      await server.close();
    }
  });

  it("allows the IdP origin in the development form-action for the logout redirect", async () => {
    vi.stubEnv("OIDC_ISSUER_URI", "http://localhost:18181/realms/x");
    const server = await createDevelopmentServer();

    try {
      expect(server.config.server.headers?.["Content-Security-Policy"]).toContain(
        "form-action 'self' http://localhost:18181;",
      );
    } finally {
      await server.close();
    }
  });

  it("does not include development-only CSP allowances in production", async () => {
    const config = await resolveConfig({}, "build", "production");
    const contentSecurityPolicy = config.server.headers?.["Content-Security-Policy"];
    if (typeof contentSecurityPolicy !== "string") {
      throw new TypeError("Production CSP header not configured");
    }

    expect(config.html?.cspNonce).toBeUndefined();
    expect(contentSecurityPolicy).not.toContain("'nonce-");
    // 本番の connect-src は自オリジンのみで、開発用の WebSocket 許可などへ拡張しない。
    const connectSrc = contentSecurityPolicy
      .split(";")
      .map((directive) => directive.trim())
      .find((directive) => directive.startsWith("connect-src"));
    expect(connectSrc).toBe("connect-src 'self'");
    expect(config.preview.headers?.["Content-Security-Policy"]).toBe(contentSecurityPolicy);
  });

  // CSP 違反の報告（ADR-068）。ブラウザが同一オリジンの /csp-report へ送る。
  describe("CSP reporting", () => {
    it("reports to the same-origin endpoint in development", async () => {
      const server = await createDevelopmentServer();

      try {
        const { headers } = server.config.server;
        expect(headers?.["Reporting-Endpoints"]).toBe(cspReportingEndpoints);
        expect(lastDirective(headers?.["Content-Security-Policy"])).toBe("report-to csp-endpoint");
      } finally {
        await server.close();
      }
    });

    it("reports to the same-origin endpoint in production and preview", async () => {
      const config = await resolveConfig({}, "build", "production");

      for (const headers of [config.server.headers, config.preview.headers]) {
        expect(headers?.["Reporting-Endpoints"]).toBe(cspReportingEndpoints);
        expect(lastDirective(headers?.["Content-Security-Policy"])).toBe("report-to csp-endpoint");
      }
    });

    it("proxies only /csp-report to the webhook_event receiver on OTEL_CSP_REPORT_HTTP_PORT", async () => {
      vi.stubEnv("FRONTEND_OTEL_ENABLED", "false");
      vi.stubEnv("OTEL_CSP_REPORT_HTTP_PORT", "19348");
      const server = await createDevelopmentServer();

      try {
        expect(server.config.server.proxy?.["^/csp-report$"]).toMatchObject({
          target: "http://localhost:19348",
          changeOrigin: false,
        });
      } finally {
        await server.close();
      }
    });

    it("proxies /csp-report in preview", async () => {
      vi.stubEnv("FRONTEND_OTEL_ENABLED", "false");
      vi.stubEnv("OTEL_CSP_REPORT_HTTP_PORT", "19349");
      const config = await resolveConfig(
        { mode: "test" },
        "serve",
        "production",
        "production",
        true,
      );

      expect(config.preview.proxy?.["^/csp-report$"]).toMatchObject({
        target: "http://localhost:19349",
        changeOrigin: false,
      });
    });

    // 実際の proxy の hop を通し、資格情報の header が Collector へ届かないことを確かめる（ADR-068）。
    it.each(["/csp-report", "/collect"])(
      "forwards %s to the collector without credentials",
      async (path) => {
        const { collector, received } = await startCollector();
        const server = await createServer({
          mode: "development",
          logLevel: "silent",
          server: { port: 0, strictPort: false, hmr: false },
        });

        try {
          await server.listen();
          const status = await postWithCredentials(
            tcpAddressOf(server.httpServer?.address()),
            path,
          );

          expect({
            status,
            path: received.path,
            type: received.headers?.["content-type"],
          }).toStrictEqual({
            status: 200,
            path,
            type: "application/reports+json",
          });
          expect(
            Object.keys(received.headers ?? {}).filter((name) => credentialHeaders.has(name)),
          ).toStrictEqual([]);
        } finally {
          await server.close();
          collector.close();
        }
      },
    );

    // ZAP の 10055-6 の除外は CSP の完全一致なので、CSP を変えたら evidence も直す（ADR-030）。
    it("keeps the ZAP alert filter evidence equal to the delivered CSP", async () => {
      vi.stubEnv("OIDC_ISSUER_URI", "http://127.0.0.1:8081/realms/spring-modulith");
      const config = await resolveConfig({}, "build", "production");
      const contentSecurityPolicy = String(config.preview.headers?.["Content-Security-Policy"]);

      for (const file of ["passive.yaml", "active.yaml"]) {
        const zapPlan = readFileSync(new URL(`../docker/zap/${file}`, import.meta.url), "utf8");
        expect(zapPlan).toContain(`evidence: "${contentSecurityPolicy}"`);
      }
    });

    it("does not send /csp-report to the backend", () => {
      expect("/csp-report").not.toMatch(new RegExp(proxyPath, "u"));
    });

    it("rejects an OTEL_CSP_REPORT_HTTP_PORT out of range", async () => {
      vi.stubEnv("FRONTEND_OTEL_ENABLED", "false");
      vi.stubEnv("OTEL_CSP_REPORT_HTTP_PORT", "65536");

      await expect(
        createServer({ mode: "development", logLevel: "silent", server: { middlewareMode: true } }),
      ).rejects.toThrow("OTEL_CSP_REPORT_HTTP_PORT must be an integer between 1 and 65535: 65536");
    });
  });

  it("allows only the IdP origin besides self in the production and preview form-action", async () => {
    vi.stubEnv("OIDC_ISSUER_URI", "http://localhost:18181/realms/x");
    const config = await resolveConfig({}, "build", "production");
    const contentSecurityPolicy = config.server.headers?.["Content-Security-Policy"];
    if (typeof contentSecurityPolicy !== "string") {
      throw new TypeError("Production CSP header not configured");
    }

    const formAction = contentSecurityPolicy
      .split(";")
      .map((directive) => directive.trim())
      .find((directive) => directive.startsWith("form-action"));
    expect(formAction).toBe("form-action 'self' http://localhost:18181");
    expect(config.preview.headers?.["Content-Security-Policy"]).toBe(contentSecurityPolicy);
  });

  it("serves preview on the pinned origin with the development proxy", async () => {
    vi.stubEnv("SERVER_PORT", "19091");
    const config = await resolveConfig({ mode: "test" }, "serve", "production", "production", true);

    expect(config.preview.port).toBe(5173);
    expect(config.preview.strictPort).toBe(true);
    expect(config.preview.proxy?.[proxyPath]).toMatchObject({
      target: "http://localhost:19091",
      changeOrigin: false,
    });
  });

  it.each(["invalid", "0x10", "65536"])("rejects invalid SERVER_PORT %s", async (value) => {
    vi.stubEnv("SERVER_PORT", value);

    // 設定の読み込み失敗を期待するテストなので、Vite が出す "failed to load config" のログを抑える。
    await expect(
      createServer({ mode: "development", logLevel: "silent", server: { middlewareMode: true } }),
    ).rejects.toThrow(`SERVER_PORT must be an integer between 1 and 65535: ${value}`);
  });

  // ローカルの .env と .env.test が結果を変えないよう、FRONTEND_OTEL_ENABLED は各テストで与える。
  describe("telemetry", () => {
    it("proxies only /collect to the faro receiver on OTEL_FARO_HTTP_PORT", async () => {
      vi.stubEnv("FRONTEND_OTEL_ENABLED", "false");
      vi.stubEnv("OTEL_FARO_HTTP_PORT", "19347");
      const server = await createDevelopmentServer();

      try {
        expect(server.config.server.proxy?.["^/collect$"]).toMatchObject({
          target: "http://localhost:19347",
          changeOrigin: false,
        });
      } finally {
        await server.close();
      }
    });

    it("does not send /collect to the backend", () => {
      expect("/collect").not.toMatch(new RegExp(proxyPath, "u"));
    });

    it("rejects an OTEL_FARO_HTTP_PORT out of range", async () => {
      vi.stubEnv("FRONTEND_OTEL_ENABLED", "false");
      vi.stubEnv("OTEL_FARO_HTTP_PORT", "65536");

      await expect(
        createServer({ mode: "development", logLevel: "silent", server: { middlewareMode: true } }),
      ).rejects.toThrow("OTEL_FARO_HTTP_PORT must be an integer between 1 and 65535: 65536");
    });

    it("rejects FRONTEND_OTEL_ENABLED other than true or false", async () => {
      vi.stubEnv("FRONTEND_OTEL_ENABLED", "yes");

      await expect(
        createServer({ mode: "development", logLevel: "silent", server: { middlewareMode: true } }),
      ).rejects.toThrow("FRONTEND_OTEL_ENABLED must be true or false: yes");
    });

    it("rejects an empty service name when enabled", async () => {
      enableTelemetry();
      vi.stubEnv("FRONTEND_OTEL_SERVICE_NAME", "");

      await expect(
        createServer({ mode: "development", logLevel: "silent", server: { middlewareMode: true } }),
      ).rejects.toThrow("Telemetry app.name must not be empty when FRONTEND_OTEL_ENABLED=true");
    });

    it("defines telemetry as disabled when FRONTEND_OTEL_ENABLED=false", async () => {
      vi.stubEnv("FRONTEND_OTEL_ENABLED", "false");
      const config = await resolveConfig({}, "build", "production");

      expect(config.define?.__TELEMETRY_ENABLED__).toBe("false");
    });

    it("defines the app version from version.txt when enabled", async () => {
      enableTelemetry();
      const config = await resolveConfig({}, "build", "production");
      const app: unknown = JSON.parse(String(config.define?.__TELEMETRY_APP__));

      expect(config.define?.__TELEMETRY_ENABLED__).toBe("true");
      expect(app).toStrictEqual({
        name: "demo-web",
        namespace: "demo",
        version: readFileSync(new URL("../version.txt", import.meta.url), "utf8").trim(),
        environment: "test",
      });
    });
  });
});
