import { ApiProblemError } from "@frontend/api/api-error";
import {
  getListMembershipsSuspenseQueryOptions,
  useSelectTenant,
} from "@frontend/api/endpoints/tenancy/tenancy";
import { Button } from "@frontend/ui/button";
import { useSuspenseQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

export function TenantsPage() {
  const { t } = useTranslation();
  const { data } = useSuspenseQuery(getListMembershipsSuspenseQueryOptions());
  const { items } = data.data;
  // テナントの選択は Idempotency-Key を送らない操作なので、自動で再試行しない（ADR-126）。
  const selectTenant = useSelectTenant({ mutation: { retry: false } });
  const problemTitle =
    selectTenant.error instanceof ApiProblemError ? selectTenant.error.problem?.title : undefined;
  const failure = selectTenant.isError ? (problemTitle ?? t("tenancy.selectFailed")) : undefined;

  if (items.length === 0) {
    return (
      <section
        className="flex flex-col gap-4 px-6 py-16 sm:px-10"
        aria-labelledby="tenants-heading"
      >
        <title>{t("tenancy.waitingHeading")}</title>
        <h1 id="tenants-heading" className="text-2xl font-semibold">
          {t("tenancy.waitingHeading")}
        </h1>
        <p className="text-muted-foreground">{t("tenancy.waitingDescription")}</p>
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-6 px-6 py-16 sm:px-10" aria-labelledby="tenants-heading">
      <title>{t("tenancy.chooseHeading")}</title>
      <div className="space-y-2">
        <h1 id="tenants-heading" className="text-2xl font-semibold">
          {t("tenancy.chooseHeading")}
        </h1>
        <p className="text-muted-foreground">{t("tenancy.chooseDescription")}</p>
      </div>
      <ul className="flex flex-col items-start gap-3">
        {items.map(({ tenantId, tenantName }) => (
          <li key={tenantId}>
            <Button
              type="button"
              disabled={selectTenant.isPending}
              onClick={() => {
                selectTenant.mutate(
                  { data: { tenantId } },
                  {
                    // 選ぶ前に読んだ画面の状態を持ち越さず、focus も文書の先頭に戻すため、文書を読み直す。
                    onSuccess: () => {
                      globalThis.location.assign("/");
                    },
                  },
                );
              }}
            >
              {tenantName}
            </Button>
          </li>
        ))}
      </ul>
      <output className="block">{failure}</output>
    </section>
  );
}
