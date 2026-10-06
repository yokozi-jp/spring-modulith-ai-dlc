import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";

import { redirectToLoginOnUnauthorized, retryUnlessClientError } from "./api-fetch";

/** 本番とテストが同じ設定で使う QueryClient を作る。 */
export function createQueryClient(): QueryClient {
  const queryClient: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: redirectToLoginOnUnauthorized }),
    mutationCache: new MutationCache({
      onError: redirectToLoginOnUnauthorized,
      // ponytail: 成功でも失敗（409 を含む）でも、描画中の関係のない query まで全部取り直す。
      // 再取得が性能の問題になったら、mutationKey か meta で絞るか、Orval の mutationInvalidates へ移る（docs/frontend/routing-and-state.md）。
      // Promise を返すので、mutation は再取得が終わるまで pending のままになる。
      onSettled: () => queryClient.invalidateQueries(),
    }),
    defaultOptions: { queries: { retry: retryUnlessClientError } },
  });
  return queryClient;
}
