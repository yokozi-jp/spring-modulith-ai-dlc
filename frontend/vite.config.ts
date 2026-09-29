import { tanstackRouter } from "@tanstack/router-plugin/vite";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite-plus";

const contentSecurityPolicy =
  "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; style-src-elem 'self' 'unsafe-inline'; style-src-attr 'none'; img-src 'self' data:; font-src 'self'; connect-src 'self'";

const securityHeaders = {
  "Content-Security-Policy": contentSecurityPolicy,
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

export default defineConfig(({ mode }) => ({
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
        "src/routeTree.gen.ts",
      ],
      thresholds: { branches: 85 },
    },
  },
  fmt: { ignorePatterns: ["src/routeTree.gen.ts"] },
  lint: {
    ignorePatterns: ["src/routeTree.gen.ts"],
    jsPlugins: [
      { name: "vite-plus", specifier: "vite-plus/oxlint-plugin" },
      { name: "shadcn", specifier: "@shadcn/lint" },
      { name: "better-tailwindcss", specifier: "eslint-plugin-better-tailwindcss" },
    ],
    rules: {
      "vite-plus/prefer-vite-plus-imports": "error",
      "react/no-danger": "error",
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
        files: ["src/components/ui/**"],
        rules: {
          "shadcn/no-restyle": "off",
          "shadcn/no-arbitrary-values": "off",
          "shadcn/require-static-classes": "off",
        },
      },
    ],
    options: { typeAware: true, typeCheck: true },
  },
  server: {
    headers: {
      ...securityHeaders,
      // ViteのWebSocketだけをローカル開発で追加許可する。
      "Content-Security-Policy":
        mode === "development"
          ? contentSecurityPolicy.replace(
              "connect-src 'self'",
              "connect-src 'self' ws://localhost:*",
            )
          : contentSecurityPolicy,
    },
    proxy: {
      "^/(api|oauth2|login|logout|error|actuator|v3/api-docs|swagger-ui)(/|$)": {
        // 開発専用の転送先。本番はCloudFront等が振り分けるためこのproxyは効かない。
        // ポートは開発バックエンド（.env の SERVER_PORT）と一致させる。両者は独立に定義され追従しない。
        // TODO: 開発者ごとにポートが変わるなら loadEnv で SERVER_PORT を読み単一ソース化する。
        target: "http://localhost:18080",
        changeOrigin: false,
      },
    },
  },
  preview: { headers: securityHeaders },
}));
