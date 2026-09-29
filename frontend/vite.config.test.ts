import { createServer } from "vite-plus";
import { describe, expect, it } from "vite-plus/test";

describe("development CSP", () => {
  it("uses the HTML nonce for the React Refresh preamble and CSP header", async () => {
    const server = await createServer({ server: { middlewareMode: true } });

    try {
      const nonce = server.config.html?.cspNonce;
      if (!nonce) throw new Error("Development CSP nonce not configured");
      expect(nonce).toMatch(/^[0-9a-f]{32}$/);
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
});
