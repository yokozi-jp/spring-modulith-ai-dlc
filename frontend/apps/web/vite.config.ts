// oxlint-disable-next-line import/no-nodejs-modules -- service.version の正本の version.txt を、ビルド時に一度だけ読む。
import { readFileSync } from "node:fs";

import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defaultExclude, defineConfig, loadEnv } from "vite-plus";
import type { ProxyOptions } from "vite-plus";

const contentSecurityPolicy =
  "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; style-src-elem 'self' 'unsafe-inline'; style-src-attr 'none'; img-src 'self' data:; font-src 'self'; connect-src 'self'; report-to csp-endpoint";

// ponytail: 開発では起動単位でnonceを固定する。本番へ適用するなら配信層でリクエスト単位に生成する。
const developmentCspNonce = crypto.randomUUID().replaceAll("-", "");

// リポジトリ直下。ルートの .env と version.txt の置き場所で、実行する場所によらず同じ場所を指す。
const repositoryRoot = `${import.meta.dirname}/../../..`;

// 生成物。カバレッジから除外する。整形と静的解析の除外はルートの vite.config.ts に書く。
const generatedFiles = ["src/routeTree.gen.ts", "src/api/generated/**"];

// ルートの.envは秘密情報も含むため、proxyに必要な変数だけを読み込む。
function portFromEnv(mode: string, name: string, fallback: string): number {
  const value = loadEnv(mode, repositoryRoot, name)[name] ?? fallback;
  const port = Number(value);
  if (!/^\d+$/u.test(value) || !Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error(`${name} must be an integer between 1 and 65535: ${value}`);
  }
  return port;
}

// Reporting API は同一オリジンの報告に Cookie を付ける。
// Collector は認証に使わないので、資格情報の header を Collector へ渡さない（ADR-068）。
// oxlint-disable-next-line func-style -- ProxyOptions の callback 型から proxy の型を推論させる。
const withoutCredentials: ProxyOptions["configure"] = (proxy) => {
  proxy.on("proxyReq", (proxyReq) => {
    for (const name of ["cookie", "authorization", "proxy-authorization"]) {
      proxyReq.removeHeader(name);
    }
  });
};

// src/lib/telemetry.ts が参照するビルド時の定数（ADR-068）。
function telemetryDefine(mode: string) {
  // ルートの.envは秘密情報も含むため、テレメトリに必要な変数だけを読み込む（VITE_接頭辞で公開しない）。
  const telemetryEnv = loadEnv(mode, repositoryRoot, [
    "FRONTEND_OTEL_",
    "OTEL_SERVICE_NAMESPACE",
    "OTEL_DEPLOYMENT_ENVIRONMENT_NAME",
  ]);
  const telemetryEnabledValue = telemetryEnv.FRONTEND_OTEL_ENABLED ?? "false";
  if (telemetryEnabledValue !== "true" && telemetryEnabledValue !== "false") {
    throw new Error(`FRONTEND_OTEL_ENABLED must be true or false: ${telemetryEnabledValue}`);
  }
  const telemetryEnabled = telemetryEnabledValue === "true";
  const telemetryApp = {
    name: telemetryEnv.FRONTEND_OTEL_SERVICE_NAME ?? "",
    namespace: telemetryEnv.OTEL_SERVICE_NAMESPACE ?? "",
    // 無効のビルドは版を使わない。
    version: telemetryEnabled ? readFileSync(`${repositoryRoot}/version.txt`, "utf8").trim() : "",
    environment: telemetryEnv.OTEL_DEPLOYMENT_ENVIRONMENT_NAME ?? "",
  };
  const emptyKey = Object.entries(telemetryApp).find(([, value]) => value === "")?.[0];
  if (telemetryEnabled && emptyKey !== undefined) {
    throw new Error(`Telemetry app.${emptyKey} must not be empty when FRONTEND_OTEL_ENABLED=true`);
  }
  return {
    __TELEMETRY_ENABLED__: JSON.stringify(telemetryEnabled),
    __TELEMETRY_APP__: JSON.stringify(telemetryApp),
  };
}

// findBy、waitFor、expect.poll が待つ上限。testTimeout より短くし、待った対象を示すエラーで失敗させる。
// ponytail: Playwright の assertion の既定と同じ値にした。これでも足りない負荷が出たら、この値だけを上げる。
const asyncWaitTimeoutMs = 5000;

// 設定から src を import せず、provide でテストへ渡す。lint/lint-project.js は apps/web の設定を一時ディレクトリへ複製し、
// src に fixture だけを置いて Lint する。設定が src を import すると、その複製の中で import を解決できず、lint 設定のテストが失敗する。
declare module "vite-plus/test" {
  interface ProvidedContext {
    asyncWaitTimeoutMs: number;
  }
}

export default defineConfig(({ mode }) => {
  const serverPort = portFromEnv(mode, "SERVER_PORT", "18080");
  const faroPort = portFromEnv(mode, "OTEL_FARO_HTTP_PORT", "12347");
  const cspReportPort = portFromEnv(mode, "OTEL_CSP_REPORT_HTTP_PORT", "12348");
  const idpOrigin = new URL(
    loadEnv(mode, repositoryRoot, "OIDC_ISSUER_URI").OIDC_ISSUER_URI ?? "http://localhost:8080",
  ).origin;
  // ログアウトのフォームはIdPへredirectされ、ChromeとSafariはredirect先にもform-actionを適用する。
  // 開発専用の許可ではないため、本番の見本（production modeとpreview）にも入れる（build-and-delivery.md）。
  const deliveredContentSecurityPolicy = contentSecurityPolicy.replace(
    "form-action 'self'",
    `form-action 'self' ${idpOrigin}`,
  );
  const developmentContentSecurityPolicy = deliveredContentSecurityPolicy
    .replace("script-src 'self'", `script-src 'self' 'nonce-${developmentCspNonce}'`)
    .replace("connect-src 'self'", "connect-src 'self' ws://localhost:*");
  const securityHeaders = {
    "Content-Security-Policy": deliveredContentSecurityPolicy,
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    // CSP の report-to が参照する endpoint（ADR-068）。default にしないので、CSP 以外の報告は送られない。
    "Reporting-Endpoints": 'csp-endpoint="/csp-report"',
  };

  return {
    ...(mode === "development" ? { html: { cspNonce: developmentCspNonce } } : {}),
    // coverage の計測だけ React Compiler を外す。Compiler が足す memo の分岐が分母に入るため（ADR-078）。
    plugins: [
      tanstackRouter({ target: "react" }),
      react({ compiler: process.env.FRONTEND_REACT_COMPILER !== "false" }),
      tailwindcss(),
    ],
    define: telemetryDefine(mode),
    // エイリアスの正本は tsconfig.json の paths とする。
    resolve: { tsconfigPaths: true },
    test: {
      exclude: [...defaultExclude, "e2e/**"],
      // pre-push では backend のテストやシークレットスキャンと並列に走り、既定の 5 秒を超えることがある。
      // ponytail: タイムアウトは性能の検証ではなくハング検出のためなので、負荷時の実測（5 秒超）に余裕を持たせた 30 秒にする。
      testTimeout: 30_000,
      expect: { poll: { timeout: asyncWaitTimeoutMs } },
      provide: { asyncWaitTimeoutMs },
      // Testing Library の待つ上限、MSW の server、後片付けを全テストで共有する（docs/frontend/testing.md）。
      setupFiles: ["./src/testing/setup.ts"],
      coverage: {
        provider: "v8",
        include: ["src/**/*.{ts,tsx}"],
        exclude: [
          "src/**/*.{test,spec}.{ts,tsx}",
          "src/**/*.d.ts",
          "src/main.tsx",
          "src/testing/**",
          ...generatedFiles,
        ],
        // json-summary は task fe-coverage がファイルごとの下限の検査に読む。
        reporter: ["text", "html", "clover", "json", "json-summary"],
        // 全体の下限。packages/ui を切り出した後の実測（branch 94.4%、line 95.64%）の少し下に置く（ADR-076）。
        thresholds: { branches: 88, lines: 95 },
      },
    },
    server: {
      // Keycloakのredirect URIと同じoriginを維持し、競合時は別ポートへ移動せず失敗させる。
      port: 5173,
      strictPort: true,
      headers: {
        ...securityHeaders,
        // ViteのWebSocketとReact Refreshのinline scriptだけをローカル開発で追加許可する。
        "Content-Security-Policy":
          mode === "development"
            ? developmentContentSecurityPolicy
            : deliveredContentSecurityPolicy,
      },
      proxy: {
        "^/(api|oauth2|login|logout|error|actuator|v3/api-docs|swagger-ui)(/|$)": {
          // 開発専用の転送先。本番はCloudFront等が振り分けるためこのproxyは効かない。
          target: `http://localhost:${serverPort}`,
          changeOrigin: false,
        },
        // ブラウザのテレメトリ（ADR-068）。Collector の faro receiver へ転送し、Spring Boot を通さない。
        // 完全一致にし、将来の /collections のような画面の path を転送しない。
        "^/collect$": {
          target: `http://localhost:${faroPort}`,
          changeOrigin: false,
          configure: withoutCredentials,
        },
        // CSP 違反の報告（ADR-068）。ブラウザが Reporting API で送り、Collector の webhook_event receiver へ転送する。
        // 完全一致にし、画面の path を転送しない。
        "^/csp-report$": {
          target: `http://localhost:${cspReportPort}`,
          changeOrigin: false,
          configure: withoutCredentials,
        },
      },
    },
    // proxy と strictPort は server の値を Vite が引き継ぐ。Keycloak の redirect URI と同じ origin で待ち受ける（ADR-033、ADR-057）。
    preview: { port: 5173, headers: securityHeaders },
  };
});
