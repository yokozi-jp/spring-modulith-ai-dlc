import { waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vite-plus/test";

describe("order with waits whose timeout the project config sets", () => {
  it("waits with expect.poll and waitFor from Testing Library", async () => {
    const done = vi.fn<() => void>();
    done();
    await expect.poll(() => done).toHaveBeenCalledOnce();
    await waitFor(() => {
      expect(done).toHaveBeenCalledOnce();
    });
  });
});
