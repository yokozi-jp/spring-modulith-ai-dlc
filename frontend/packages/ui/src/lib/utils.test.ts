import { cn } from "@frontend/ui/lib/utils";
import { describe, expect, it } from "vite-plus/test";

describe("cn", () => {
  it("競合する Tailwind の utility は最後のものを残す", () => {
    expect(cn("p-2", "p-4")).toBe("p-4");
  });
});
