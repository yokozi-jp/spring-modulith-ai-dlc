import type { TenantMemberResponse } from "@frontend/api/models";
import { Button } from "@frontend/ui/button";
import { useTranslation } from "react-i18next";

const roles = ["ADMIN", "MEMBER"] as const;

const roleLabels = {
  ADMIN: "tenancy.members.role.admin",
  MEMBER: "tenancy.members.role.member",
} as const satisfies Record<(typeof roles)[number], string>;

export function MembersTable({
  items,
  isPending,
  onChangeRole,
  onRemove,
}: {
  items: readonly TenantMemberResponse[];
  isPending: boolean;
  onChangeRole: (userId: string, role: string) => void;
  onRemove: (userId: string) => void;
}) {
  const { t } = useTranslation();

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
                  onChangeRole(userId, event.currentTarget.value);
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
                  onRemove(userId);
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
