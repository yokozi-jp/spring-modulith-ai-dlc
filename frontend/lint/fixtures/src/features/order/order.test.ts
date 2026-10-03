import { render } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

describe("order", () => {
  it("uses Testing Library", () => {
    expect(render).toBeTypeOf("function");
  });
});
