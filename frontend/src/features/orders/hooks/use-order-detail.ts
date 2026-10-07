import { useSuspenseQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import { getFindOrderByIdSuspenseQueryOptions } from "@/api/generated/endpoints/ordering/ordering";
import { getListProductsSuspenseQueryOptions } from "@/api/generated/endpoints/product/product";
import type { Notice } from "@/features/orders/notice";

import { useOrderLinesEditor } from "./use-order-lines-editor";
import { useOrderStatusActions } from "./use-order-status-actions";

/** 詳細の画面の data と、明細の変更、確定、取消が共有する 1 つの通知。 */
export function useOrderDetail(orderId: string) {
  const { t } = useTranslation();
  const { data: order } = useSuspenseQuery(getFindOrderByIdSuspenseQueryOptions(orderId));
  const { data: products } = useSuspenseQuery(getListProductsSuspenseQueryOptions());
  const [notice, setNotice] = useState<Notice>();
  const editor = useOrderLinesEditor(orderId, order.data, setNotice);
  const actions = useOrderStatusActions(orderId, order.data.lockNo, setNotice);
  const pending = editor.isPending || actions.isPending;

  return {
    order: order.data,
    products: products.data.items ?? [],
    editor,
    actions,
    notice: pending ? { heading: t("orders.form.submitting") } : notice,
    // lockNo か orderId がない詳細では、更新の操作を出さない。
    editable:
      order.data.status === "DRAFT" &&
      order.data.lockNo !== undefined &&
      order.data.orderId !== undefined,
  };
}
