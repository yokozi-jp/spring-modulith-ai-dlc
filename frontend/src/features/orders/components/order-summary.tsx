import { useTranslation } from "react-i18next";

import type { OrderDetailsResponse } from "@/api/generated/models";
import { orderStatusLabel } from "@/features/orders/order-status";

// 作成の時刻は出さない（Safari の Temporal の対応と polyfill の要否を決めていないため。#122）。
// 決済の状態は手順 3 で足す。
export function OrderSummary({ order }: { order: OrderDetailsResponse }) {
  const { t } = useTranslation();
  const missing = t("orders.missingValue");

  return (
    <dl className="grid max-w-md grid-cols-2 gap-x-4 gap-y-2">
      <dt className="text-muted-foreground">{t("orders.fields.customerOrderCode")}</dt>
      <dd>{order.customerOrderCode ?? missing}</dd>
      <dt className="text-muted-foreground">{t("orders.fields.status")}</dt>
      <dd>{orderStatusLabel(t, order.status)}</dd>
      <dt className="text-muted-foreground">{t("orders.fields.totalAmount")}</dt>
      <dd>
        {order.totalAmount === undefined
          ? missing
          : t("orders.amount", { value: order.totalAmount })}
      </dd>
    </dl>
  );
}
