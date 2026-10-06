import type { TFunction } from "i18next";
import { z as zod } from "zod";

import {
  ChangeOrderLinesBody,
  changeOrderLinesBodyLinesMax,
  DraftOrderBody,
  draftOrderBodyCustomerOrderCodeMax,
  draftOrderBodyLinesItemQuantityMax,
  draftOrderBodyLinesMax,
} from "@/api/generated/zod/order/order.zod";

/** 入力欄の値。数量は input の値のまま文字列で持ち、送信時の検証で数値にする。 */
export interface OrderLineValues {
  /** React の key に使う、画面の中だけの識別子。送信しない。 */
  rowKey: string;
  productId: string;
  quantity: string;
}

export interface OrderLinesValues {
  lines: OrderLineValues[];
}

export interface DraftOrderValues extends OrderLinesValues {
  customerOrderCode: string;
}

export function newLine(): OrderLineValues {
  return { rowKey: crypto.randomUUID(), productId: "", quantity: "1" };
}
export const quantityMin = 1;
export const quantityMax = draftOrderBodyLinesItemQuantityMax;
export const customerOrderCodeMax = draftOrderBodyCustomerOrderCodeMax;

// 生成 Zod は明細の件数を min(0)、数量を任意としているため、画面の入力条件（1 行以上、数量は必須）を重ねる。
function orderLinesSchema(t: TFunction, max: number) {
  const quantityRange = t("orders.form.errors.quantityRange", {
    min: quantityMin,
    max: quantityMax,
  });
  const line = zod
    .object({
      rowKey: zod.string(),
      productId: zod.uuid({ error: t("orders.form.errors.productRequired") }),
      quantity: zod
        .string()
        .trim()
        .min(1, { error: t("orders.form.errors.quantityRequired") })
        .transform(Number)
        .pipe(
          zod
            .number({ error: quantityRange })
            .int({ error: quantityRange })
            .min(quantityMin, { error: quantityRange })
            .max(quantityMax, { error: quantityRange }),
        ),
    })
    // rowKey は画面の中だけの値なので、送る本文から外す。
    .transform(({ productId, quantity }) => ({ productId, quantity }));
  return zod
    .array(line)
    .min(1, { error: t("orders.form.errors.linesRequired") })
    .max(max, { error: t("orders.form.errors.linesTooMany", { max }) });
}

/** 作成の送信時の検証。客先注文番号は前後の空白を除いて 1 文字以上 30 文字以下にする。 */
export function draftOrderFormSchema(t: TFunction) {
  return DraftOrderBody.extend({
    customerOrderCode: zod
      .string()
      .trim()
      .min(1, { error: t("orders.form.errors.customerOrderCodeRequired") })
      .max(customerOrderCodeMax, {
        error: t("orders.form.errors.customerOrderCodeTooLong", { max: customerOrderCodeMax }),
      }),
    lines: orderLinesSchema(t, draftOrderBodyLinesMax),
  });
}

/** 明細の変更の送信時の検証。lockNo は form の値に持たず、送るときに足す。 */
export function changeOrderLinesFormSchema(t: TFunction) {
  return ChangeOrderLinesBody.pick({ lines: true }).extend({
    lines: orderLinesSchema(t, changeOrderLinesBodyLinesMax),
  });
}
