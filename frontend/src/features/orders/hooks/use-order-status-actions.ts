import { useTranslation } from "react-i18next";

import { useCancelOrder, useConfirmOrder } from "@/api/generated/endpoints/ordering/ordering";
import { problemNotice, problemStatus } from "@/features/orders/notice";
import type { Notice } from "@/features/orders/notice";

/**
 * 確定と取消。押した時点の表示中の lockNo を本文で送る（表示している版と送る版が同じなので、黙って差し込まない）。
 * 409 は通知だけにし、最新の表示は全 query の無効化に任せる。
 */
export function useOrderStatusActions(
  orderId: string,
  lockNo: number | undefined,
  setNotice: (notice: Notice | undefined) => void,
) {
  const { t } = useTranslation();
  const confirmMutation = useConfirmOrder();
  const cancelMutation = useCancelOrder();
  const data = lockNo === undefined ? {} : { lockNo };
  const isPending = confirmMutation.isPending || cancelMutation.isPending;

  const onError = (error: unknown) => {
    const status = problemStatus(error);
    if (status === 409) {
      setNotice(problemNotice(t, error, t("orders.notice.actionConflict")));
    } else if (status === 422) {
      setNotice(problemNotice(t, error, t("orders.notice.actionUnprocessable")));
    } else {
      setNotice({
        heading: t("orders.notice.generalTitle"),
        description: t("orders.notice.generalFailure"),
      });
    }
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
    // 成功すると DRAFT でなくなって form ごと入力が消える。main に入れるときは shadcn の AlertDialog を足し、dirty の間は確認を挟む。
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
