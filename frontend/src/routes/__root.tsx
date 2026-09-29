import type { QueryClient } from "@tanstack/react-query";
import { createRootRouteWithContext } from "@tanstack/react-router";

// loaderから queryClient.ensureQueryData などを呼べるよう、ルーターのcontextで受け渡す。
export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()();
