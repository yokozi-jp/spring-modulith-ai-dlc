import { isTenantNotSelected } from "@frontend/api/api-error";
import { getFindCurrentTenantSuspenseQueryOptions } from "@frontend/api/endpoints/tenancy/tenancy";
import { useSuspenseQuery } from "@tanstack/react-query";
import { createFileRoute, Link, Outlet, redirect } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { preloadQuery } from "@/api/preload-query";

function TenantLayout() {
  const { t } = useTranslation();
  const { data } = useSuspenseQuery(getFindCurrentTenantSuspenseQueryOptions());

  return (
    <>
      <div className="flex items-center justify-between gap-4 border-b px-6 py-2 text-sm sm:px-10">
        <p className="text-muted-foreground">
          {t("tenancy.currentTenant", { tenantName: data.data.tenantName })}
        </p>
        <Link to="/members" className="underline-offset-4 hover:underline">
          {t("tenancy.manageMembers")}
        </Link>
      </div>
      <Outlet />
    </>
  );
}

export const Route = createFileRoute("/_authenticated/_tenant")({
  beforeLoad: async ({ context }) => {
    try {
      await preloadQuery(context.queryClient, getFindCurrentTenantSuspenseQueryOptions());
    } catch (error) {
      if (isTenantNotSelected(error)) {
        // oxlint-disable-next-line typescript/only-throw-error -- TanStack Router は redirect を throw で受け取る。
        throw redirect({ to: "/tenants" });
      }
      throw error;
    }
  },
  component: TenantLayout,
});
