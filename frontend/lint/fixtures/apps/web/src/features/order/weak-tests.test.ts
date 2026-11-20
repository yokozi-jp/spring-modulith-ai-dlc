import { describe, expect, it } from "vite-plus/test";

describe("注文のテストを弱める記述", () => {
  it.only("焦点を当てたテストを残す", () => {
    expect(1).toBe(1);
  });

  it.skip("無効にしたテストを残す", () => {
    expect(1).toBe(1);
  });

  it("expect のないテストを残す", () => {
    const total = 1 + 1;
    void total;
  });

  it("分岐の中だけに expect を置く", () => {
    const total = Date.now();
    if (total > 0) {
      expect(total).toBeGreaterThan(0);
    }
  });

  it("matcher のない expect を残す", () => {
    expect(1);
  });
});
