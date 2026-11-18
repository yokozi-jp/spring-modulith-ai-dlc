// Node 24 は Temporal をフラグなしで持たないため、テストでは常に polyfill を入れる（native があれば native を使う）。
// oxlint-disable-next-line import/no-unassigned-import -- グローバルの Temporal を入れる副作用だけの import である。
import "temporal-polyfill/global";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, vi } from "vite-plus/test";

import { server } from "./msw";

// Vitest の setupFiles。Node と jsdom の全テストで、MSW の server と後片付けをまとめる（docs/frontend/testing.md）。
// oxlint-disable vitest/require-top-level-describe -- setupFiles の hook は全テストに効かせるため、describe の外に置く。
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(async () => {
  cleanup();
  server.resetHandlers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
  // @/i18n は import の時点で document を使うため（languageChanged の listener）、jsdom のテストでだけ読み込む。
  if (typeof document !== "undefined") {
    const { i18n } = await import("@/i18n");
    await i18n.changeLanguage("ja");
  }
});

afterAll(() => {
  server.close();
});
