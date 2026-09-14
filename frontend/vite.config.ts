import { defineConfig } from "vite-plus";

const contentSecurityPolicy =
  "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'";

const securityHeaders = {
  "Content-Security-Policy": contentSecurityPolicy,
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

export default defineConfig(({ mode }) => ({
  fmt: {},
  lint: {
    jsPlugins: [{ name: "vite-plus", specifier: "vite-plus/oxlint-plugin" }],
    rules: { "vite-plus/prefer-vite-plus-imports": "error" },
    options: { typeAware: true, typeCheck: true },
  },
  server: {
    headers: {
      ...securityHeaders,
      // ViteのCSS HMRとWebSocketだけをローカル開発で追加許可する。
      "Content-Security-Policy":
        mode === "development"
          ? contentSecurityPolicy
              .replace("style-src 'self'", "style-src 'self' 'unsafe-inline'")
              .replace("connect-src 'self'", "connect-src 'self' ws://localhost:*")
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
