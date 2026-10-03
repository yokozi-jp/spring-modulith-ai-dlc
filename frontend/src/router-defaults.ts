import { RouteError } from "@/components/route-error";
import { RouteNotFound } from "@/components/route-not-found";
import { RoutePending } from "@/components/route-pending";

// main.tsx とルートのテストが同じ既定値でルーターを作るよう、ここにまとめる。
export const routerDefaults = {
  defaultPendingComponent: RoutePending,
  defaultErrorComponent: RouteError,
  defaultNotFoundComponent: RouteNotFound,
  // リンクへのホバーやフォーカスで遷移先のコードとloaderを先読みする。
  defaultPreload: "intent",
  // 先読みしたデータの鮮度はTanStack Queryに任せ、ルーター側ではキャッシュしない。
  defaultPreloadStaleTime: 0,
  // ブラウザの戻る操作や進む操作で、遷移前のスクロール位置を復元する。
  scrollRestoration: true,
} as const;
