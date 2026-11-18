import { describe, expect, it, vi } from "vite-plus/test";

describe("timeout を 1 秒に固定した待ちを持つ注文", () => {
  it("vi.waitFor と vi.waitUntil で待つ", async () => {
    const done = vi.fn<() => void>();
    done();
    await vi.waitFor(() => {
      expect(done).toHaveBeenCalledOnce();
    });
    await vi.waitUntil(() => done.mock.calls.length === 1);
  });
});
