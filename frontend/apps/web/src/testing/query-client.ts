import type { QueryClient } from "@tanstack/react-query";

import { createQueryClient } from "@/api/query-client";

/**
 * 本番と同じ設定の QueryClient から、query の再試行を外し、mutation の再試行の待ちを 0 にする。失敗の表示を待たずに確かめるため。
 * mutation を再試行するかの判定は本番のまま残す。
 */
export function createTestQueryClient(): QueryClient {
  const queryClient = createQueryClient();
  const defaults = queryClient.getDefaultOptions();
  queryClient.setDefaultOptions({
    queries: { ...defaults.queries, retry: false },
    mutations: { ...defaults.mutations, retryDelay: 0 },
  });
  return queryClient;
}
