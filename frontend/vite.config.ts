// oxlint-disable max-lines -- Vite、Lint、テストの設定を1つのdefineConfigに集める正本のため、行数で分割しない。
// oxlint-disable-next-line import/no-nodejs-modules -- service.version の正本の version.txt を、ビルド時に一度だけ読む。
import { readFileSync } from "node:fs";

import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defaultExclude, defineConfig, loadEnv } from "vite-plus";

const contentSecurityPolicy =
  "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; style-src-elem 'self' 'unsafe-inline'; style-src-attr 'none'; img-src 'self' data:; font-src 'self'; connect-src 'self'";

// ponytail: 開発では起動単位でnonceを固定する。本番へ適用するなら配信層でリクエスト単位に生成する。
const developmentCspNonce = crypto.randomUUID().replaceAll("-", "");

const restrictedHtmlProperties = [
  "innerHTML",
  "outerHTML",
  "insertAdjacentHTML",
  "createContextualFragment",
  "setHTMLUnsafe",
  "parseHTMLUnsafe",
  "DOMParser",
  "srcdoc",
].map((property) => ({ property, message: "Use React children or textContent instead." }));

// override の option は前の指定とマージされず置き換わるため、禁止の一覧を定数に分けて override ごとに組み合わせる。
const htmlSinkGlobals = [{ name: "DOMParser", message: "Do not parse arbitrary HTML." }];
const apiMessage = "Use the generated API client in src/api.";
const mockMessage = "Use MSW, vi.spyOn or vi.stubGlobal instead of module mocks.";
const networkGlobals = ["fetch", "XMLHttpRequest"].map((name) => ({ name, message: apiMessage }));
const htmlSinkProperties = [
  ...restrictedHtmlProperties,
  { object: "document", property: "write", message: "Do not write HTML directly." },
  { object: "document", property: "writeln", message: "Do not write HTML directly." },
];
const networkProperties = [
  { object: "window", property: "fetch", message: apiMessage },
  { object: "globalThis", property: "fetch", message: apiMessage },
];
const sharedLayerImports = {
  group: ["@/features/**", "@/routes/**", "@/routeTree.gen"],
  message: "Shared code must not depend on features or routes.",
};
const featureImports = {
  group: ["@/routes/**", "@/routeTree.gen"],
  message: "Use getRouteApi instead of importing a route.",
};
const baseUiImports = {
  group: ["@base-ui/**"],
  message: "Import UI primitives from @/components/ui (ADR-025).",
};
const testOnlyImports = {
  group: ["msw", "msw/**", "@/api/generated/mocks/**", "@/testing/**", "@testing-library/**"],
  message: "Use test-only modules only in test files and src/testing.",
};
// ponytail: no-restricted-imports は静的な import だけを見る。feature からの動的 import("@grafana/...") は通る。
// 違反が起きたら、lint/feature-boundaries.js と同じく ImportExpression を見る規則に移す。
const telemetrySdkImports = {
  group: ["@grafana/*"],
  message: "Call the telemetry SDK only through src/lib/telemetry.ts (ADR-066).",
};

// 生成物。整形・静的解析・カバレッジのいずれからも除外する。
const generatedFiles = ["src/routeTree.gen.ts", "src/api/generated/**"];
// lint 設定のテストが使う、違反を含む fixture。通常の整形、静的解析、テストの収集から外す。
const lintFixtures = ["lint/fixtures/**"];

// ルートの.envは秘密情報も含むため、proxyに必要な変数だけを読み込む。
function portFromEnv(mode: string, name: string, fallback: string): number {
  const value = loadEnv(mode, "..", name)[name] ?? fallback;
  const port = Number(value);
  if (!/^\d+$/u.test(value) || !Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error(`${name} must be an integer between 1 and 65535: ${value}`);
  }
  return port;
}

// src/lib/telemetry.ts が参照するビルド時の定数（ADR-066）。
function telemetryDefine(mode: string) {
  // ルートの.envは秘密情報も含むため、テレメトリに必要な変数だけを読み込む（VITE_接頭辞で公開しない）。
  const telemetryEnv = loadEnv(mode, "..", [
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
    // 無効のビルドは版を使わない。lint 設定のテストは、この設定を version.txt のない一時ディレクトリへ複製して読む。
    version: telemetryEnabled
      ? readFileSync(new URL("../version.txt", import.meta.url), "utf8").trim()
      : "",
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

export default defineConfig(({ mode }) => {
  const serverPort = portFromEnv(mode, "SERVER_PORT", "18080");
  const faroPort = portFromEnv(mode, "OTEL_FARO_HTTP_PORT", "12347");
  const idpOrigin = new URL(
    loadEnv(mode, "..", "OIDC_ISSUER_URI").OIDC_ISSUER_URI ?? "http://localhost:8080",
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
  };

  return {
    ...(mode === "development" ? { html: { cspNonce: developmentCspNonce } } : {}),
    plugins: [tanstackRouter({ target: "react" }), react({ compiler: true }), tailwindcss()],
    define: telemetryDefine(mode),
    // エイリアスの正本は tsconfig.json の paths とする。
    resolve: { tsconfigPaths: true },
    test: {
      exclude: [...defaultExclude, ...lintFixtures, "e2e/**"],
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
        thresholds: { branches: 85 },
      },
    },
    fmt: { ignorePatterns: [...generatedFiles, ...lintFixtures], sortImports: true },
    lint: {
      ignorePatterns: [...generatedFiles, ...lintFixtures],
      categories: {
        correctness: "error",
        suspicious: "error",
        pedantic: "error",
        perf: "error",
        style: "error",
        restriction: "error",
      },
      plugins: [
        "eslint",
        "unicorn",
        "typescript",
        "oxc",
        "react",
        "import",
        "vitest",
        "jsx-a11y",
        "promise",
      ],
      jsPlugins: [
        { name: "vite-plus", specifier: "vite-plus/oxlint-plugin" },
        { name: "local-security", specifier: "./lint/local-security.js" },
        { name: "shadcn", specifier: "@shadcn/lint" },
        { name: "better-tailwindcss", specifier: "eslint-plugin-better-tailwindcss" },
        { name: "feature-boundaries", specifier: "./lint/feature-boundaries.js" },
      ],
      rules: {
        // automatic JSX runtime、Vite設定、TanStack Routerの規約と両立しない規則。
        "react/react-in-jsx-scope": "off",
        "react/forbid-component-props": "off",
        "react/jsx-filename-extension": ["error", { extensions: [".jsx", ".tsx"] }],
        "react/jsx-max-depth": "off",
        "react/jsx-props-no-spreading": "off",
        "import/no-default-export": "off",
        "import/no-named-export": "off",
        "import/prefer-default-export": "off",
        "import/exports-last": "off",
        "import/group-exports": "off",
        "import/no-unassigned-import": ["error", { allow: ["**/*.css"] }],
        // modern TypeScriptとReactで一般的な構文を一律禁止するrestriction規則。
        "oxc/no-async-await": "off",
        "oxc/no-optional-chaining": "off",
        "oxc/no-rest-spread-properties": "off",
        // event handler で reject しない Promise を捨てる `void` 文と、`x === undefined` の比較は許す。
        "no-void": ["error", { allowAsStatement: true }],
        "no-undefined": "off",
        "typescript/explicit-function-return-type": "off",
        "typescript/explicit-module-boundary-types": "off",
        "typescript/prefer-readonly-parameter-types": "off",
        "typescript/promise-function-async": "off",
        // Oxfmtや型推論と役割が重複するか、可読性を下げる一律のstyle規則。
        "capitalized-comments": "off",
        "func-style": "off",
        // react-i18next の慣用名である翻訳関数 t だけを短い識別子として許可する。
        "id-length": ["error", { exceptions: ["t"] }],
        // vite.config.ts の define が置き換えるビルド時の定数だけを、ほかの識別子と衝突しない名前として許す（ADR-066）。
        "no-underscore-dangle": [
          "error",
          { allow: ["__TELEMETRY_ENABLED__", "__TELEMETRY_APP__"] },
        ],
        "max-lines-per-function": "off",
        "no-duplicate-imports": ["error", { allowSeparateTypeImports: true }],
        "no-magic-numbers": "off",
        "no-ternary": "off",
        "one-var": "off",
        "sort-imports": "off",
        "sort-keys": "off",
        // Vitestの標準APIと競合するか、相互に矛盾するtest style規則。
        "vitest/no-conditional-in-test": "off",
        "vitest/no-hooks": "off",
        "vitest/no-importing-vitest-globals": "off",
        "vitest/prefer-called-times": "off",
        "vitest/prefer-describe-function-title": "off",
        "vitest/prefer-expect-assertions": "off",
        "vitest/prefer-lowercase-title": "off",
        "vitest/prefer-strict-boolean-matchers": "off",
        "vitest/prefer-to-be-truthy": "off",
        "vitest/require-hook": "off",
        "vitest/require-test-timeout": "off",
        "vite-plus/prefer-vite-plus-imports": "error",
        "react/no-danger": "error",
        "vitest/no-restricted-vi-methods": ["error", { mock: mockMessage, doMock: mockMessage }],
        "no-restricted-imports": [
          "error",
          { patterns: [baseUiImports, testOnlyImports, telemetrySdkImports] },
        ],
        "no-restricted-globals": ["error", ...htmlSinkGlobals, ...networkGlobals],
        "no-restricted-properties": ["error", ...htmlSinkProperties, ...networkProperties],
        "local-security/no-jsx-srcdoc": "error",
        "feature-boundaries/no-cross-feature-import": "error",
        "shadcn/no-restyle": ["error", { allow: ["layout"] }],
        "shadcn/no-raw-colors": "error",
        "shadcn/no-arbitrary-values": ["error", { allow: ["layout"] }],
        "shadcn/no-inline-styles": "error",
        "shadcn/require-static-classes": "error",
        "shadcn/no-unknown-classes": "error",
        "better-tailwindcss/enforce-consistent-class-order": "error",
        "better-tailwindcss/no-deprecated-classes": "error",
        "better-tailwindcss/no-duplicate-classes": "error",
        "better-tailwindcss/no-unnecessary-whitespace": "error",
        "better-tailwindcss/no-conflicting-classes": "error",
        "better-tailwindcss/enforce-canonical-classes": "error",
      },
      settings: {
        "better-tailwindcss": { entryPoint: "src/style.css" },
      },
      // ponytail: 同じ rule を指定する override は、後に一致したものの option だけが効く。
      // 正しさは override の並び順と、option が置き換わることに依存する。並べ替えるときは lint/lint-config.test.js で確かめる。
      overrides: [
        {
          files: ["lint/**"],
          rules: {
            "import/no-nodejs-modules": "off",
            "new-cap": "off",
            "typescript/no-unsafe-assignment": "off",
            "typescript/no-unsafe-call": "off",
            "typescript/no-unsafe-member-access": "off",
          },
        },
        {
          files: ["src/{api,components,lib,i18n}/**"],
          rules: {
            "no-restricted-imports": [
              "error",
              {
                patterns: [sharedLayerImports, baseUiImports, testOnlyImports, telemetrySdkImports],
              },
            ],
          },
        },
        // テレメトリの SDK を呼ぶ唯一のファイル（ADR-066）。
        {
          files: ["src/lib/telemetry.ts"],
          rules: {
            "no-restricted-imports": [
              "error",
              { patterns: [sharedLayerImports, baseUiImports, testOnlyImports] },
            ],
          },
        },
        {
          files: ["src/features/**"],
          rules: {
            "no-restricted-imports": [
              "error",
              { patterns: [featureImports, baseUiImports, testOnlyImports, telemetrySdkImports] },
            ],
          },
        },
        {
          files: ["src/components/ui/**"],
          rules: {
            "shadcn/no-restyle": "off",
            "shadcn/no-arbitrary-values": "off",
            "shadcn/require-static-classes": "off",
            "no-restricted-imports": [
              "error",
              { patterns: [sharedLayerImports, testOnlyImports, telemetrySdkImports] },
            ],
            "react/only-export-components": "off",
          },
        },
        {
          files: ["src/api/**"],
          rules: {
            "no-restricted-globals": ["error", ...htmlSinkGlobals],
            "no-restricted-properties": ["error", ...htmlSinkProperties],
          },
        },
        {
          files: ["src/routes/**"],
          rules: {
            "react/only-export-components": "off",
          },
        },
        {
          files: ["**/*.{test,spec}.{ts,tsx,js,jsx}"],
          rules: {
            "react/jsx-no-literals": "off",
          },
        },
        // Playwright Test の API に vitest plugin の規則が当たるため。
        {
          files: ["e2e/**"],
          rules: {
            "vitest/consistent-test-filename": "off",
            "vitest/prefer-importing-vitest-globals": "off",
          },
        },
        // ponytail: option が置き換わるため、テストでは層の import 制限も外れる。
        // テストにも層の制限が要るようになったら dependency-cruiser へ移る。
        {
          files: ["**/*.{test,spec}.{ts,tsx,js,jsx}", "src/testing/**"],
          rules: {
            "no-restricted-imports": ["error", { patterns: [baseUiImports, telemetrySdkImports] }],
          },
        },
      ],
      options: {
        denyWarnings: true,
        reportUnusedDisableDirectives: "error",
        typeAware: true,
        typeCheck: true,
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
        // ブラウザのテレメトリ（ADR-066）。Collector の faro receiver へ転送し、Spring Boot を通さない。
        // 完全一致にし、将来の /collections のような画面の path を転送しない。
        "^/collect$": {
          target: `http://localhost:${faroPort}`,
          changeOrigin: false,
        },
      },
    },
    // proxy と strictPort は server の値を Vite が引き継ぐ。Keycloak の redirect URI と同じ origin で待ち受ける（ADR-033、ADR-057）。
    preview: { port: 5173, headers: securityHeaders },
  };
});
