import { describe, expect, it } from "vite-plus/test";

describe("package の exports", () => {
  it("部品を公開し、テストファイルを公開しない", () => {
    expect(import.meta.resolve("@frontend/ui/button")).toMatch(/\/src\/button\.tsx$/u);
    expect(() => import.meta.resolve("@frontend/ui/button.test")).toThrow(
      /not defined|not exported|Missing/iu,
    );
  });
});
