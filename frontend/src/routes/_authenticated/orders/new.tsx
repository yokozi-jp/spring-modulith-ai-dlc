import { createFileRoute } from "@tanstack/react-router";

import { getListProductsSuspenseQueryOptions } from "@/api/generated/endpoints/product/product";
import { preloadQuery } from "@/api/preload-query";
import { OrderNewPage } from "@/features/orders/order-new-page";

export const Route = createFileRoute("/_authenticated/orders/new")({
  loader: ({ context }) => preloadQuery(context.queryClient, getListProductsSuspenseQueryOptions()),
  component: OrderNewPage,
});
