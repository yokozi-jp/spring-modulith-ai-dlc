// oxlint-disable max-lines -- Vite、Lint、テストの設定を1つのdefineConfigに集める正本のため、行数で分割しない。
import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defaultExclude, defineConfig, loadEnv } from "vite-plus";

const contentSecurityPolicy =
  "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; style-src-elem 'self' 'unsafe-inline'; style-src-attr 'none'; img-src 'self' data:; font-src 'self'; connect-src 'self'";

// ponytail: 開発では起動単位でnonceを固定する。本番へ適用するなら配信層でリクエスト単位に生成する。
const developmentCspNonce = crypto.randomUUID().replaceAll("-", "");
const developmentContentSecurityPolicy = contentSecurityPolicy
  .replace("script-src 'self'", `script-src 'self' 'nonce-${developmentCspNonce}'`)
  .replace("connect-src 'self'", "connect-src 'self' ws://localhost:*");

const securityHeaders = {
  "Content-Security-Policy": contentSecurityPolicy,
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

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

// 生成物。整形・静的解析・カバレッジのいずれからも除外する。
const generatedFiles = ["src/routeTree.gen.ts", "src/api/generated/**"];
// lint 設定のテストが使う、違反を含む fixture。通常の整形、静的解析、テストの収集から外す。
const lintFixtures = ["lint/fixtures/**"];

export default defineConfig(({ mode }) => {
  // ルートの.envは秘密情報も含むため、proxyに必要な変数だけを読み込む。
  const serverPortValue = loadEnv(mode, "..", "SERVER_PORT").SERVER_PORT ?? "18080";
  const serverPort = Number(serverPortValue);
  if (
    !/^\d+$/u.test(serverPortValue) ||
    !Number.isInteger(serverPort) ||
    serverPort < 1 ||
    serverPort > 65_535
  ) {
    throw new Error(`SERVER_PORT must be an integer between 1 and 65535: ${serverPortValue}`);
  }
  // ログアウトのフォームはIdPへredirectされ、ChromeとSafariはredirect先にもform-actionを適用する。
  const idpOrigin = new URL(
    loadEnv(mode, "..", "OIDC_ISSUER_URI").OIDC_ISSUER_URI ?? "http://localhost:8080",
  ).origin;

  return {
    ...(mode === "development" ? { html: { cspNonce: developmentCspNonce } } : {}),
    plugins: [tanstackRouter({ target: "react" }), react({ compiler: true }), tailwindcss()],
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
        "no-restricted-imports": ["error", { patterns: [baseUiImports, testOnlyImports] }],
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
              { patterns: [sharedLayerImports, baseUiImports, testOnlyImports] },
            ],
          },
        },
        {
          files: ["src/features/**"],
          rules: {
            "no-restricted-imports": [
              "error",
              { patterns: [featureImports, baseUiImports, testOnlyImports] },
            ],
          },
        },
        {
          files: ["src/components/ui/**"],
          rules: {
            "shadcn/no-restyle": "off",
            "shadcn/no-arbitrary-values": "off",
            "shadcn/require-static-classes": "off",
            "no-restricted-imports": ["error", { patterns: [sharedLayerImports, testOnlyImports] }],
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
            "no-restricted-imports": ["error", { patterns: [baseUiImports] }],
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
            ? developmentContentSecurityPolicy.replace(
                "form-action 'self'",
                `form-action 'self' ${idpOrigin}`,
              )
            : contentSecurityPolicy,
      },
      proxy: {
        "^/(api|oauth2|login|logout|error|actuator|v3/api-docs|swagger-ui)(/|$)": {
          // 開発専用の転送先。本番はCloudFront等が振り分けるためこのproxyは効かない。
          target: `http://localhost:${serverPort}`,
          changeOrigin: false,
        },
      },
    },
    // proxy と strictPort は server の値を Vite が引き継ぐ。Keycloak の redirect URI と同じ origin で待ち受ける（ADR-033、ADR-057）。
    preview: { port: 5173, headers: securityHeaders },
  };
});
