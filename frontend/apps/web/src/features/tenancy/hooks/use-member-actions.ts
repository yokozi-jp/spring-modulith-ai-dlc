import {
  useChangeTenantMemberRole,
  useRemoveTenantMember,
} from "@frontend/api/endpoints/tenancy/tenancy";
import { useTranslation } from "react-i18next";

import { failureNotice } from "@/lib/notice";
import type { Notice } from "@/lib/notice";

/**
 * 役割の変更と所属の取り外し。押した時点の表示中の lockNo を送る（表示している版と送る版が同じなので、黙って差し込まない）。
 * 409 は通知だけにし、最新の一覧は全 query の無効化に任せる。自動で再送しない。
 */
export function useMemberActions(lockNo: number, setNotice: (notice: Notice | undefined) => void) {
  const { t } = useTranslation();
  // 毎回作り直される mutation の結果ではなく、安定した mutate を取り出して React Compiler の memo を効かせる。
  const { mutate: changeRole, isPending: isChanging } = useChangeTenantMemberRole();
  const { mutate: removeMember, isPending: isRemoving } = useRemoveTenantMember();

  function callbacks(success: string) {
    return {
      onSuccess: () => {
        setNotice({ heading: success });
      },
      onError: (error: unknown) => {
        setNotice(
          failureNotice(t, error, {
            notFound: t("tenancy.members.notice.notFound"),
            conflict: t("tenancy.members.notice.conflict"),
            unprocessable: t("tenancy.members.notice.lastAdmin"),
          }),
        );
      },
    };
  }

  return {
    isPending: isChanging || isRemoving,
    handleChangeRole: (userId: string, role: string) => {
      setNotice(undefined);
      changeRole(
        { userId, data: { role, lockNo } },
        callbacks(t("tenancy.members.notice.roleChanged")),
      );
    },
    handleRemove: (userId: string) => {
      setNotice(undefined);
      removeMember({ userId, params: { lockNo } }, callbacks(t("tenancy.members.notice.removed")));
    },
  };
}
