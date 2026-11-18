import { describe, expect, it, test } from "vite-plus/test";

describe("english describe title", () => {
  it("english it title", () => {
    expect(1).toBe(1);
  });

  test.skip("english test title", () => {
    expect(1).toBe(1);
  });

  it.each([1, 2])("english each title %i", (value) => {
    expect(value).toBeGreaterThan(0);
  });
});

describe("resolveLocale", () => {
  it("日本語の title を許可する", () => {
    expect(1).toBe(1);
  });

  it(`english template title`, () => {
    expect(1).toBe(1);
  });
});
