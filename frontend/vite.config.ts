import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite-plus";

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

// 生成物。整形・静的解析・カバレッジのいずれからも除外する。
const generatedFiles = ["src/routeTree.gen.ts"];

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

  return {
    ...(mode === "development" ? { html: { cspNonce: developmentCspNonce } } : {}),
    plugins: [tanstackRouter({ target: "react" }), react({ compiler: true }), tailwindcss()],
    // エイリアスの正本は tsconfig.json の paths とする。
    resolve: { tsconfigPaths: true },
    test: {
      coverage: {
        provider: "v8",
        include: ["src/**/*.{ts,tsx}"],
        exclude: [
          "src/**/*.{test,spec}.{ts,tsx}",
          "src/**/*.d.ts",
          "src/main.tsx",
          ...generatedFiles,
        ],
        thresholds: { branches: 85 },
      },
    },
    fmt: { ignorePatterns: generatedFiles, sortImports: true },
    lint: {
      ignorePatterns: generatedFiles,
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
        "no-restricted-globals": [
          "error",
          { name: "DOMParser", message: "Do not parse arbitrary HTML." },
        ],
        "no-restricted-properties": [
          "error",
          ...restrictedHtmlProperties,
          { object: "document", property: "write", message: "Do not write HTML directly." },
          { object: "document", property: "writeln", message: "Do not write HTML directly." },
        ],
        "local-security/no-jsx-srcdoc": "error",
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
      },
      settings: {
        "better-tailwindcss": { entryPoint: "src/style.css" },
      },
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
          files: ["src/components/ui/**"],
          rules: {
            "shadcn/no-restyle": "off",
            "shadcn/no-arbitrary-values": "off",
            "shadcn/require-static-classes": "off",
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
          mode === "development" ? developmentContentSecurityPolicy : contentSecurityPolicy,
      },
      proxy: {
        "^/(api|oauth2|login|logout|error|actuator|v3/api-docs|swagger-ui)(/|$)": {
          // 開発専用の転送先。本番はCloudFront等が振り分けるためこのproxyは効かない。
          target: `http://localhost:${serverPort}`,
          changeOrigin: false,
        },
      },
    },
    preview: { headers: securityHeaders },
  };
});
