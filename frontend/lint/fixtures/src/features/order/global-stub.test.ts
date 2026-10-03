import { afterEach, describe, expect, it, vi } from "vite-plus/test";

describe("order with replaced globals", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("replaces globals", () => {
    vi.stubGlobal("matchMedia", () => ({ matches: true }));
    const scrollTo = vi.spyOn(globalThis, "scrollTo").mockReturnValue();
    expect(scrollTo).not.toHaveBeenCalled();
  });
});
