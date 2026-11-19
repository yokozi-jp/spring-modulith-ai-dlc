import { getListMembershipsSuspenseQueryOptions } from "@frontend/api/endpoints/tenancy/tenancy";
import { createFileRoute } from "@tanstack/react-router";

import { preloadQuery } from "@/api/preload-query";
import { TenantsPage } from "@/features/tenancy/tenants-page";

export const Route = createFileRoute("/_authenticated/tenants")({
  loader: ({ context }) =>
    preloadQuery(context.queryClient, getListMembershipsSuspenseQueryOptions()),
  component: TenantsPage,
});
