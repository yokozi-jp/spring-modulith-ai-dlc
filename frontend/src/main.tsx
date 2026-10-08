import "./style.css";
import { QueryClientProvider } from "@tanstack/react-query";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";

import { createQueryClient } from "./api/query-client";
import { i18n } from "./i18n";
import { bindRouterTelemetry, initTelemetry } from "./lib/telemetry";
import { routerDefaults } from "./router-defaults";
import { routeTree } from "./routeTree.gen";

// SDK は動的 import で読み、描画を待たせない。
initTelemetry();

const queryClient = createQueryClient();

const router = createRouter({ routeTree, context: { queryClient }, ...routerDefaults });
const unbindRouterTelemetry = bindRouterTelemetry(router);
if (import.meta.hot) {
  import.meta.hot.dispose(unbindRouterTelemetry);
}

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

const rootElement = document.querySelector<HTMLElement>("#root");
if (!rootElement) {
  throw new Error("#root element not found");
}

// Temporal を出荷していないブラウザ（2026 年 10 月時点の Safari）だけ polyfill を読み込む（ADR-047、#122 の P-15）。
if (!("Temporal" in globalThis)) {
  await import("temporal-polyfill/global");
}

createRoot(rootElement).render(
  <StrictMode>
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nextProvider>
  </StrictMode>,
);
