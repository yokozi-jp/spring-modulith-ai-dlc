import { ApiProblemError } from "@frontend/api/api-fetch";
import { getFindOrderByIdSuspenseQueryOptions } from "@frontend/api/endpoints/ordering/ordering";
import { getListProductsSuspenseQueryOptions } from "@frontend/api/endpoints/product/product";
import { createFileRoute, notFound } from "@tanstack/react-router";

import { preloadQuery } from "@/api/preload-query";
import { OrderDetailPage } from "@/features/orders/order-detail-page";

export const Route = createFileRoute("/_authenticated/_tenant/orders/$orderId/")({
  loader: async ({ context, params }) => {
    try {
      await Promise.all([
        preloadQuery(context.queryClient, getFindOrderByIdSuspenseQueryOptions(params.orderId)),
        preloadQuery(context.queryClient, getListProductsSuspenseQueryOptions()),
      ]);
    } catch (error) {
      // 400 は path の UUID の形の誤りで、利用者には存在しない注文と同じに見せる。
      if (error instanceof ApiProblemError && (error.status === 404 || error.status === 400)) {
        // TanStack Router は notFound() の戻り値を投げる形で not found を扱う。
        // oxlint-disable-next-line typescript/only-throw-error
        throw notFound();
      }
      throw error;
    }
  },
  component: OrderDetailPage,
});
