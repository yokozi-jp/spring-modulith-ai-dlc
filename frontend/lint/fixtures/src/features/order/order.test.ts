import { render } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

describe("注文", () => {
  it("Testing Library を使う", () => {
    expect(render).toBeTypeOf("function");
  });
});
