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
        target: "http://localhost:18080",
        changeOrigin: false,
      },
    },
  },
  preview: { headers: securityHeaders },
}));
