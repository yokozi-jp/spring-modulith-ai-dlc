import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext } from "@tanstack/react-router";

// loaderから preloadQuery（src/api/preload-query.ts）などで queryClient を使えるよう、ルーターのcontextで受け渡す。
export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()();
