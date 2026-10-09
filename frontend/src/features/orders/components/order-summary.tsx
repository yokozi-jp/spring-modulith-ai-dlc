import { useTranslation } from "react-i18next";

import type { OrderDetailsResponse } from "@/api/generated/models";
import { orderStatusLabel } from "@/features/orders/order-status";

import { PaymentStatus } from "./payment-status";

// この画面は作成の時刻を出さない。出すときは ADR-047 に従い Temporal.Instant で読む。
// 決済の状態は、注文の要約の後に別の見出しの欄で出す。
export function OrderSummary({ order, orderId }: { order: OrderDetailsResponse; orderId: string }) {
  const { t } = useTranslation();
  const missing = t("orders.missingValue");

  return (
    <>
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
      <PaymentStatus orderId={orderId} orderStatus={order.status} />
    </>
  );
}
