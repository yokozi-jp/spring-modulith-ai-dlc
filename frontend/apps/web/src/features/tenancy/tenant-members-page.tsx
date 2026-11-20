import { ApiProblemError } from "@frontend/api/api-fetch";
import { getListTenantMembersSuspenseQueryOptions } from "@frontend/api/endpoints/tenancy/tenancy";
import { useSuspenseQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import { ProblemNotice } from "@/components/problem-notice";
import { MembersTable } from "@/features/tenancy/members-table";
import type { Notice } from "@/lib/notice";

export function TenantMembersPage() {
  const { t } = useTranslation();
  const { data, error } = useSuspenseQuery(getListTenantMembersSuspenseQueryOptions());
  const [notice, setNotice] = useState<Notice | undefined>();

  // 自分の役割を下げる、自分の所属を外すと、操作の後の一覧の再取得が 403 になる。
  // 取得済みの一覧があると useSuspenseQuery は投げないため、ここで投げて route の errorComponent に渡す。
  if (error instanceof ApiProblemError && error.status === 403) {
    throw error;
  }

  return (
    <section className="flex flex-col gap-6 px-6 py-10 sm:px-10" aria-labelledby="members-heading">
      <title>{t("tenancy.members.title")}</title>
      <div className="space-y-2">
        <h1 id="members-heading" className="text-2xl font-semibold">
          {t("tenancy.members.title")}
        </h1>
        <p className="text-muted-foreground">{t("tenancy.members.description")}</p>
      </div>
      <MembersTable lockNo={data.data.lockNo} items={data.data.items} onNotice={setNotice} />
      <ProblemNotice notice={notice} />
    </section>
  );
}
