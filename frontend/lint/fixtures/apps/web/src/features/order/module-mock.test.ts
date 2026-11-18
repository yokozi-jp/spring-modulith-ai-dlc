import { describe, expect, it, vi } from "vite-plus/test";

vi.mock("@/api/client");

describe("order with module mocks", () => {
  it("mocks modules", async () => {
    vi.doMock("@/lib/clean");
    await expect(import("@/lib/clean")).resolves.toBeDefined();
  });
});
