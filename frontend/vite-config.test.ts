// oxlint-disable-next-line import/no-nodejs-modules -- 設定が読む version.txt を、テストでも同じ場所から読んで比べる。
import { readFileSync } from "node:fs";

import { createServer, resolveConfig } from "vite-plus";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

const proxyPath = "^/(api|oauth2|login|logout|error|actuator|v3/api-docs|swagger-ui)(/|$)";

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
