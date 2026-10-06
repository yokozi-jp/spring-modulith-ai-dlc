import "./style.css";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { I18nextProvider } from "react-i18next";

import { redirectToLoginOnUnauthorized, retryUnlessClientError } from "./api/api-fetch";
import { i18n } from "./i18n";
import { routerDefaults } from "./router-defaults";
import { routeTree } from "./routeTree.gen";

const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: redirectToLoginOnUnauthorized }),
  mutationCache: new MutationCache({ onError: redirectToLoginOnUnauthorized }),
  defaultOptions: { queries: { retry: retryUnlessClientError } },
});

const router = createRouter({ routeTree, context: { queryClient }, ...routerDefaults });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

const rootElement = document.querySelector<HTMLElement>("#root");
if (!rootElement) {
  throw new Error("#root element not found");
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
