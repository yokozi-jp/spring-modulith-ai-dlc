import { createServer, resolveConfig } from "vite-plus";
import { afterEach, describe, expect, it, vi } from "vite-plus/test";

const proxyPath = "^/(api|oauth2|login|logout|error|actuator|v3/api-docs|swagger-ui)(/|$)";

const createDevelopmentServer = () =>
  createServer({ mode: "development", server: { middlewareMode: true } });

describe("Vite configuration", () => {
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

  it("does not include development-only CSP allowances in production", async () => {
    const config = await resolveConfig({}, "build", "production");
    const contentSecurityPolicy = config.server.headers?.["Content-Security-Policy"];
    if (typeof contentSecurityPolicy !== "string") {
      throw new TypeError("Production CSP header not configured");
    }

    expect(config.html?.cspNonce).toBeUndefined();
    expect(contentSecurityPolicy).not.toContain("'nonce-");
    expect(contentSecurityPolicy).not.toContain("ws://");
    expect(config.preview.headers?.["Content-Security-Policy"]).toBe(contentSecurityPolicy);
  });

  it.each(["invalid", "0x10", "65536"])("rejects invalid SERVER_PORT %s", async (value) => {
    vi.stubEnv("SERVER_PORT", value);

    await expect(createDevelopmentServer()).rejects.toThrow(
      `SERVER_PORT must be an integer between 1 and 65535: ${value}`,
    );
  });
});
