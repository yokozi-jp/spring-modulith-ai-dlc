import { getListOrdersSuspenseQueryOptions } from "@frontend/api/endpoints/ordering/ordering";
import { ListOrdersQueryParams } from "@frontend/api/zod/ordering/ordering.zod";
import { createFileRoute } from "@tanstack/react-router";

import { preloadQuery } from "@/api/preload-query";
import { isOrderStatus, listOrdersParams } from "@/features/orders/order-status";
import { OrdersPage } from "@/features/orders/pages/orders-page";

export const Route = createFileRoute("/_authenticated/_tenant/orders/")({
  // 不正な status は捨てて全件を表示する（エラー画面にしない）。
  // oxlint-disable-next-line promise/prefer-await-to-then, unicorn/prefer-top-level-await -- Zod の catch で、Promise の catch ではない。
  validateSearch: ListOrdersQueryParams.catch({}),
  loaderDeps: ({ search }) => ({
    status: isOrderStatus(search.status) ? search.status : undefined,
  }),
  // ponytail: ページングせず全件を一度に取る。件数が増えたら ADR-013 のページングを API と画面に足す。
  loader: ({ context, deps }) =>
    preloadQuery(
      context.queryClient,
      getListOrdersSuspenseQueryOptions(listOrdersParams(deps.status)),
    ),
  component: OrdersPage,
});
