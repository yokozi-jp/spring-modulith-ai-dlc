import type { QueryClient } from "@tanstack/react-query";

import { createQueryClient } from "@/api/query-client";

/** 本番と同じ設定の QueryClient から、query の再試行だけを外す。失敗の表示を待たずに確かめるため。 */
export function createTestQueryClient(): QueryClient {
  const queryClient = createQueryClient();
  const defaults = queryClient.getDefaultOptions();
  queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retry: false } });
  return queryClient;
}
