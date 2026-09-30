import "./style.css";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { routeTree } from "./routeTree.gen";

const queryClient = new QueryClient();

const router = createRouter({
  routeTree,
  context: { queryClient },
  // リンクへのホバーやフォーカスで遷移先のコードとloaderを先読みする。
  defaultPreload: "intent",
  // 先読みしたデータの鮮度はTanStack Queryに任せ、ルーター側ではキャッシュしない。
  defaultPreloadStaleTime: 0,
  // ブラウザの戻る操作や進む操作で、遷移前のスクロール位置を復元する。
  scrollRestoration: true,
});

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
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
