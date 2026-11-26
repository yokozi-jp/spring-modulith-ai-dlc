import { getListTenantMembersSuspenseQueryOptions } from "@frontend/api/endpoints/tenancy/tenancy";
import { createFileRoute } from "@tanstack/react-router";

import { preloadQuery } from "@/api/preload-query";
import { TenantMembersPage } from "@/features/tenancy/pages/tenant-members-page";

export const Route = createFileRoute("/_authenticated/_tenant/members")({
  loader: ({ context }) =>
    preloadQuery(context.queryClient, getListTenantMembersSuspenseQueryOptions()),
  component: TenantMembersPage,
});
