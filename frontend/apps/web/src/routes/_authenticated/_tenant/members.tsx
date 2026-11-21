import { ApiProblemError, isTenantNotSelected } from "@frontend/api/api-fetch";
import { getListTenantMembersSuspenseQueryOptions } from "@frontend/api/endpoints/tenancy/tenancy";
import { createFileRoute } from "@tanstack/react-router";
import type { ErrorComponentProps } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { preloadQuery } from "@/api/preload-query";
import { RouteError } from "@/components/route-error";
import { TenantMembersPage } from "@/features/tenancy/tenant-members-page";

// 管理者でない利用者には backend が権限不足の 403 を返す。再試行しても同じなので、管理者だけの画面だと伝える。
// テナントを選んでいない 403 は、RouteError がテナントを選び直す画面へ案内する。
function MembersError(props: ErrorComponentProps) {
  const { t } = useTranslation();
  if (
    props.error instanceof ApiProblemError &&
    props.error.status === 403 &&
    !isTenantNotSelected(props.error)
  ) {
    return (
      <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10" role="alert">
        <h1 className="text-2xl font-semibold">{t("tenancy.members.forbiddenHeading")}</h1>
        <p className="text-muted-foreground">{t("tenancy.members.forbiddenDescription")}</p>
      </section>
    );
  }
  return <RouteError {...props} />;
}

export const Route = createFileRoute("/_authenticated/_tenant/members")({
  loader: ({ context }) =>
    preloadQuery(context.queryClient, getListTenantMembersSuspenseQueryOptions()),
  component: TenantMembersPage,
  errorComponent: MembersError,
});
