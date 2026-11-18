import { afterAll, afterEach, beforeAll, vi } from "vite-plus/test";

import { server } from "./msw";

// Vitest の setupFiles。MSW の server と後片付けを、この package の全テストでまとめる。
// oxlint-disable vitest/require-top-level-describe -- setupFiles の hook は全テストに効かせるため、describe の外に置く。
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

afterAll(() => {
  server.close();
});
