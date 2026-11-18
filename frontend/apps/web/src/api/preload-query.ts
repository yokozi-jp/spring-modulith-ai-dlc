import type { QueryClient, QueryKey, UseSuspenseQueryOptions } from "@tanstack/react-query";

/**
 * loader で、component の useSuspenseQuery と同じ生成 query options の data を cache に入れる。
 * cache に data があれば取得せずに返す（ensureQueryData の後継の query）。
 * mutation の後に無効化された data だけは取得し直し、遷移した画面に古い値を描画しない。
 * suspense 用の型は queryFn を省略可能としており、exactOptionalPropertyTypes の下では query へそのまま渡せないため、ここで詰め替える。
 */
export function preloadQuery<TQueryFnData, TError, TData, TQueryKey extends QueryKey>(
  queryClient: QueryClient,
  options: UseSuspenseQueryOptions<TQueryFnData, TError, TData, TQueryKey>,
): Promise<TData> {
  const { queryFn, ...rest } = options;
  return queryClient.query({
    ...rest,
    ...(queryFn === undefined ? {} : { queryFn }),
    staleTime: (query) => (query.state.isInvalidated ? 0 : "static"),
  });
}
