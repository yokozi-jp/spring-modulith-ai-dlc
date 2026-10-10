import { useTranslation } from "react-i18next";

import { useCancelOrder, useConfirmOrder } from "@/api/generated/endpoints/ordering/ordering";
import { failureNotice } from "@/features/orders/notice";
import type { Notice } from "@/features/orders/notice";

/**
 * 確定と取消。押した時点の表示中の lockNo を本文で送る（表示している版と送る版が同じなので、黙って差し込まない）。
 * 409 は通知だけにし、最新の表示は全 query の無効化に任せる。
 */
export function useOrderStatusActions(
  orderId: string,
  lockNo: number,
  setNotice: (notice: Notice | undefined) => void,
) {
  const { t } = useTranslation();
  const confirmMutation = useConfirmOrder();
  const cancelMutation = useCancelOrder();
  const data = { lockNo };
  const isPending = confirmMutation.isPending || cancelMutation.isPending;

  const onError = (error: unknown) => {
    setNotice(
      failureNotice(t, error, {
        conflict: t("orders.notice.actionConflict"),
        unprocessable: t("orders.notice.actionUnprocessable"),
      }),
    );
  };

  return {
    isPending,
    handleConfirm: () => {
      if (isPending) {
        return;
      }
      setNotice(undefined);
      confirmMutation.mutate(
        { orderId, data },
        {
          onSuccess: () => {
            setNotice({ heading: t("orders.notice.confirmed") });
          },
          onError,
        },
      );
    },
    // ponytail: 取消は戻せないが、確認の dialog を挟まずに送る。明細の変更の form が dirty でも確定と取消を送り、
    // 成功すると DRAFT でなくなって form ごと入力が消える。確認の dialog を挟むのは #186 で扱う。
    handleCancel: () => {
      if (isPending) {
        return;
      }
      setNotice(undefined);
      cancelMutation.mutate(
        { orderId, data },
        {
          onSuccess: () => {
            setNotice({ heading: t("orders.notice.cancelled") });
          },
          onError,
        },
      );
    },
  };
}
