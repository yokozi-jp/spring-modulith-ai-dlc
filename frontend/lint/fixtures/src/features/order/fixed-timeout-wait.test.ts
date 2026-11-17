import { describe, expect, it, vi } from "vite-plus/test";

describe("order with waits whose timeout is fixed at 1 second", () => {
  it("waits with vi.waitFor and vi.waitUntil", async () => {
    const done = vi.fn<() => void>();
    done();
    await vi.waitFor(() => {
      expect(done).toHaveBeenCalledOnce();
    });
    await vi.waitUntil(() => done.mock.calls.length === 1);
  });
});
