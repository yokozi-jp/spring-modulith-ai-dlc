import { createFileRoute } from "@tanstack/react-router";

import { getListOrdersSuspenseQueryOptions } from "@/api/generated/endpoints/order/order";
import { ListOrdersQueryParams } from "@/api/generated/zod/order/order.zod";
import { preloadQuery } from "@/api/preload-query";
import { isOrderStatus, listOrdersParams } from "@/features/orders/order-status";
import { OrdersPage } from "@/features/orders/orders-page";

export const Route = createFileRoute("/_authenticated/orders/")({
  // 不正な status は捨てて全件を表示する（エラー画面にしない）。
  // Zod の catch で、Promise の catch ではない。
  // oxlint-disable-next-line promise/prefer-await-to-then, unicorn/prefer-top-level-await
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
