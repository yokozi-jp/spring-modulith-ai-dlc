import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext } from "@tanstack/react-router";

import { RouteNotFound } from "@/components/route-not-found";

// どの route にも一致しない URL の 404 はアプリシェルの外に出るので、main の landmark で包む。
// 配下の route の 404 はアプリシェルの main の中に出るので、router の既定の RouteNotFound のままにする。
function RootNotFound() {
  return (
    <main>
      <RouteNotFound />
    </main>
  );
}

// loaderから preloadQuery（src/api/preload-query.ts）などで queryClient を使えるよう、ルーターのcontextで受け渡す。
interface Context {
  queryClient: QueryClient;
}

export const Route = createRootRouteWithContext<Context>()({ notFoundComponent: RootNotFound });
