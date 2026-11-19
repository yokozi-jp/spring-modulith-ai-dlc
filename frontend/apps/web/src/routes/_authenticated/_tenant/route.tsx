import { isTenantNotSelected } from "@frontend/api/api-fetch";
import { getFindCurrentTenantSuspenseQueryOptions } from "@frontend/api/endpoints/tenancy/tenancy";
import { useSuspenseQuery } from "@tanstack/react-query";
import { createFileRoute, Outlet, redirect } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { preloadQuery } from "@/api/preload-query";

function TenantLayout() {
  const { t } = useTranslation();
  const { data } = useSuspenseQuery(getFindCurrentTenantSuspenseQueryOptions());

  return (
    <>
      <p className="border-b px-6 py-2 text-sm text-muted-foreground sm:px-10">
        {t("tenancy.currentTenant", { tenantName: data.data.tenantName })}
      </p>
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
