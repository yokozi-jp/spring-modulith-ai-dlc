import { waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vite-plus/test";

describe("project config が timeout を決める待ちを持つ注文", () => {
  it("expect.poll と Testing Library の waitFor で待つ", async () => {
    const done = vi.fn<() => void>();
    done();
    await expect.poll(() => done).toHaveBeenCalledOnce();
    await waitFor(() => {
      expect(done).toHaveBeenCalledOnce();
    });
  });
});
