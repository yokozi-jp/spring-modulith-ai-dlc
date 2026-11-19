import { ApiProblemError } from "@frontend/api/api-fetch";
import {
  getListTenantMembersSuspenseQueryOptions,
  useChangeTenantMemberRole,
  useRemoveTenantMember,
} from "@frontend/api/endpoints/tenancy/tenancy";
import { Button } from "@frontend/ui/button";
import { useSuspenseQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";

const roles = ["ADMIN", "MEMBER"] as const;

const roleLabels = {
  ADMIN: "tenancy.members.role.admin",
  MEMBER: "tenancy.members.role.member",
} as const satisfies Record<(typeof roles)[number], string>;

/** 画面の live region に出す通知。heading は backend の title、description は操作ごとの catalog の文。 */
interface Notice {
  heading: string;
  description?: string;
}

export function TenantMembersPage() {
  const { t } = useTranslation();
  const { data } = useSuspenseQuery(getListTenantMembersSuspenseQueryOptions());
  const { lockNo, items } = data.data;
  const [notice, setNotice] = useState<Notice | undefined>();
  // 毎回作り直される mutation の結果ではなく、安定した mutate を取り出して React Compiler の memo を効かせる。
  const { mutate: changeRole, isPending: isChanging } = useChangeTenantMemberRole();
  const { mutate: removeMember, isPending: isRemoving } = useRemoveTenantMember();
  const isPending = isChanging || isRemoving;

  // 409 は最新の一覧を取り直して通知するだけにし、自動で再送せず、新しい lockNo も黙って差し込まない。
  function onError(error: unknown) {
    const status = error instanceof ApiProblemError ? error.status : undefined;
    const title = error instanceof ApiProblemError ? error.problem?.title : undefined;
    const descriptions: Partial<Record<number, string>> = {
      404: t("tenancy.members.notice.notFound"),
      409: t("tenancy.members.notice.conflict"),
      422: t("tenancy.members.notice.lastAdmin"),
    };
    setNotice({
      heading: title ?? t("tenancy.members.notice.generalTitle"),
      description:
        (status === undefined ? undefined : descriptions[status]) ??
        t("tenancy.members.notice.generalFailure"),
    });
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
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b">
            <th scope="col" className="py-2">
              {t("tenancy.members.userId")}
            </th>
            <th scope="col" className="py-2">
              {t("tenancy.members.roleColumn")}
            </th>
            <th scope="col" className="py-2">
              {t("tenancy.members.actionColumn")}
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map(({ userId, role }) => (
            <tr key={userId} className="border-b">
              <td className="py-2 font-mono">{userId}</td>
              <td className="py-2">
                <select
                  className="rounded-md border bg-background px-2 py-1"
                  aria-label={t("tenancy.members.roleOf", { userId })}
                  value={role}
                  disabled={isPending}
                  onChange={(event) => {
                    setNotice(undefined);
                    changeRole(
                      { userId, data: { role: event.currentTarget.value, lockNo } },
                      {
                        onSuccess: () => {
                          setNotice({ heading: t("tenancy.members.notice.roleChanged") });
                        },
                        onError,
                      },
                    );
                  }}
                >
                  {roles.map((value) => (
                    <option key={value} value={value}>
                      {t(roleLabels[value])}
                    </option>
                  ))}
                </select>
              </td>
              <td className="py-2">
                <Button
                  type="button"
                  variant="destructive"
                  size="sm"
                  disabled={isPending}
                  onClick={() => {
                    setNotice(undefined);
                    removeMember(
                      { userId, params: { lockNo } },
                      {
                        onSuccess: () => {
                          setNotice({ heading: t("tenancy.members.notice.removed") });
                        },
                        onError,
                      },
                    );
                  }}
                >
                  {t("tenancy.members.remove", { userId })}
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <output className="block space-y-1">
        {notice === undefined ? undefined : (
          <>
            <strong className="block">{notice.heading}</strong>
            {notice.description === undefined ? undefined : (
              <span className="block text-muted-foreground">{notice.description}</span>
            )}
          </>
        )}
      </output>
    </section>
  );
}
