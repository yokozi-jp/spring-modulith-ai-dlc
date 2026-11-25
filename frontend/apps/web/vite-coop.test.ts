import { resolveConfig } from "vite-plus";
import { describe, expect, it } from "vite-plus/test";

// vite-config.test.ts は外側の describe の max-statements に当たるため、COOP の検査を別のファイルに置く。
// 開発サーバー、build、preview は同じ securityHeaders を配り、モードで変わるのは CSP だけである。
describe("HTML の応答に付ける COOP（ADR-132）", () => {
  it("server と preview の Cross-Origin-Opener-Policy を same-origin にする", async () => {
    const config = await resolveConfig({}, "build", "production");

    expect(config.server.headers?.["Cross-Origin-Opener-Policy"]).toBe("same-origin");
    expect(config.preview.headers?.["Cross-Origin-Opener-Policy"]).toBe("same-origin");
  });
});
