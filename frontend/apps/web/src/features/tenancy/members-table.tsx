import {
  useChangeTenantMemberRole,
  useRemoveTenantMember,
} from "@frontend/api/endpoints/tenancy/tenancy";
import type { TenantMemberResponse } from "@frontend/api/models";
import { Button } from "@frontend/ui/button";
import { useTranslation } from "react-i18next";

import { failureNotice } from "@/lib/notice";
import type { Notice } from "@/lib/notice";

const roles = ["ADMIN", "MEMBER"] as const;

const roleLabels = {
  ADMIN: "tenancy.members.role.admin",
  MEMBER: "tenancy.members.role.member",
} as const satisfies Record<(typeof roles)[number], string>;

export function MembersTable({
  lockNo,
  items,
  onNotice,
}: {
  lockNo: number;
  items: readonly TenantMemberResponse[];
  onNotice: (notice?: Notice) => void;
}) {
  const { t } = useTranslation();
  // 毎回作り直される mutation の結果ではなく、安定した mutate を取り出して React Compiler の memo を効かせる。
  const { mutate: changeRole, isPending: isChanging } = useChangeTenantMemberRole();
  const { mutate: removeMember, isPending: isRemoving } = useRemoveTenantMember();
  const isPending = isChanging || isRemoving;

  // 409 は最新の一覧を取り直して通知するだけにし、自動で再送せず、新しい lockNo も黙って差し込まない。
  function callbacks(success: string) {
    return {
      onSuccess: () => {
        onNotice({ heading: success });
      },
      onError: (failure: unknown) => {
        onNotice(
          failureNotice(t, failure, {
            notFound: t("tenancy.members.notice.notFound"),
            conflict: t("tenancy.members.notice.conflict"),
            unprocessable: t("tenancy.members.notice.lastAdmin"),
          }),
        );
      },
    };
  }

  return (
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
                  onNotice();
                  changeRole(
                    { userId, data: { role: event.currentTarget.value, lockNo } },
                    callbacks(t("tenancy.members.notice.roleChanged")),
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
                  onNotice();
                  removeMember(
                    { userId, params: { lockNo } },
                    callbacks(t("tenancy.members.notice.removed")),
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
  );
}
