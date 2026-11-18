import { afterEach, describe, expect, it, vi } from "vite-plus/test";

describe("global を差し替えた注文", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("global を差し替える", () => {
    vi.stubGlobal("matchMedia", () => ({ matches: true }));
    const scrollTo = vi.spyOn(globalThis, "scrollTo").mockReturnValue();
    expect(scrollTo).not.toHaveBeenCalled();
  });
});
