import type { TFunction } from "i18next";

import type { ListOrdersParams } from "@/api/generated/models";

export const orderStatuses = ["DRAFT", "CONFIRMED", "CANCELLED"] as const;
export type OrderStatus = (typeof orderStatuses)[number];

// 生成型の status は string なので、表示名の catalog の key をここで対応づける。
const orderStatusLabels = {
  DRAFT: "orders.status.draft",
  CONFIRMED: "orders.status.confirmed",
  CANCELLED: "orders.status.cancelled",
} as const satisfies Record<OrderStatus, string>;

const salesStatusLabels = {
  ON_SALE: "orders.salesStatus.onSale",
  DISCONTINUED: "orders.salesStatus.discontinued",
} as const satisfies Record<"ON_SALE" | "DISCONTINUED", string>;

export function isOrderStatus(value: string | undefined): value is OrderStatus {
  return value !== undefined && Object.hasOwn(orderStatusLabels, value);
}

/** 対応にない値はコード値をそのまま、値がなければ catalog の「なし」を返す。 */
export function orderStatusLabel(t: TFunction, status?: string): string {
  if (status === undefined) {
    return t("orders.missingValue");
  }
  return isOrderStatus(status) ? t(orderStatusLabels[status]) : status;
}

export function salesStatusLabel(t: TFunction, status?: string): string {
  if (status === "ON_SALE" || status === "DISCONTINUED") {
    return t(salesStatusLabels[status]);
  }
  return status ?? t("orders.missingValue");
}

/** loader と component が同じ query key を作るよう、一覧の引数をここで組む。 */
export function listOrdersParams(status: OrderStatus | undefined): ListOrdersParams | undefined {
  return status === undefined ? undefined : { status };
}
