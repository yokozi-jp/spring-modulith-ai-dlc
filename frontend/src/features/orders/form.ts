import { createFormHook, createFormHookContexts } from "@tanstack/react-form";
import type { TFunction } from "i18next";

import type { ProductSummaryResponse } from "@/api/generated/models";

import { salesStatusLabel } from "./order-status";

const { fieldContext, formContext } = createFormHookContexts();

// 作成と明細の変更の form が、明細の入力欄（withFieldGroup）を共有するために使う。
export const { useAppForm, withFieldGroup } = createFormHook({
  fieldContext,
  formContext,
  fieldComponents: {},
  formComponents: {},
});

/** TanStack Form の errors（Standard Schema の issue）から最初の文言を取る。 */
export function firstErrorMessage(errors: readonly unknown[]): string | undefined {
  const [error] = errors;
  return typeof error === "object" && error !== null && "message" in error
    ? String(error.message)
    : undefined;
}

export interface ProductOption {
  productId: string;
  label: string;
}

/** 販売中の商品と、選択中の販売終了の商品だけを選択肢にする。 */
export function productOptions(
  t: TFunction,
  products: readonly ProductSummaryResponse[],
  selectedId: string,
): ProductOption[] {
  const options: ProductOption[] = [];
  for (const { productId, productName, salesStatus } of products) {
    const name = productName ?? productId ?? "";
    if (productId === undefined) {
      // 識別子のない商品は選べない。
    } else if (salesStatus === "ON_SALE") {
      options.push({ productId, label: name });
    } else if (productId === selectedId) {
      options.push({
        productId,
        label: t("orders.form.discontinuedProduct", {
          name,
          status: salesStatusLabel(t, salesStatus),
        }),
      });
    }
  }
  return options;
}
