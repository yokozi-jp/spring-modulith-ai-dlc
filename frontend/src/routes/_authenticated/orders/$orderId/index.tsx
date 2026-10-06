import { createFileRoute, notFound } from "@tanstack/react-router";

import { ApiProblemError } from "@/api/api-fetch";
import { getFindOrderByIdSuspenseQueryOptions } from "@/api/generated/endpoints/order/order";
import { getListProductsSuspenseQueryOptions } from "@/api/generated/endpoints/product/product";
import { preloadQuery } from "@/api/preload-query";
import { OrderDetailPage } from "@/features/orders/order-detail-page";

export const Route = createFileRoute("/_authenticated/orders/$orderId/")({
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
