import { useState } from "react";
import { describe, expect, it } from "vite-plus/test";

function useHarness() {
  return useState(0);
}

describe("注文", () => {
  it("テストの補助の Hook で React を使う", () => {
    expect(useHarness).toBeTypeOf("function");
  });
});
