import { resolveConfig } from "vite-plus";
import { describe, expect, it } from "vite-plus/test";

// 開発サーバー、build、preview は同じ securityHeaders を配る。ADR-132 が COOP を HTML の応答に求める。
describe("HTML の応答に付ける COOP", { timeout: 60_000 }, () => {
  it.each([
    { name: "開発サーバー", command: "serve", mode: "development" },
    { name: "本番の server と preview", command: "build", mode: "production" },
  ] as const)(
    "$name は Cross-Origin-Opener-Policy を same-origin にする",
    async ({ command, mode }) => {
      const config = await resolveConfig({}, command, mode);

      expect(config.server.headers?.["Cross-Origin-Opener-Policy"]).toBe("same-origin");
      expect(config.preview.headers?.["Cross-Origin-Opener-Policy"]).toBe("same-origin");
    },
  );
});
