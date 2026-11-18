import { describe, expect, it, vi } from "vite-plus/test";

vi.mock("@/api/client");

describe("module を差し替えた注文", () => {
  it("module を差し替える", async () => {
    vi.doMock("@/lib/clean");
    await expect(import("@/lib/clean")).resolves.toBeDefined();
  });
});
