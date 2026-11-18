import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import type { OrderSummaryResponse } from "@/api/generated/models";
import { orderStatusLabel } from "@/features/orders/order-status";

// この画面は作成の時刻を出さない。出すときは ADR-047 に従い Temporal.Instant で読む。
export function OrdersTable({ items }: { items: OrderSummaryResponse[] }) {
  const { t } = useTranslation();

  return (
    <table className="w-full text-left text-sm">
      <thead>
        <tr className="border-b">
          <th scope="col" className="py-2">
            {t("orders.fields.customerOrderCode")}
          </th>
          <th scope="col" className="py-2">
            {t("orders.fields.status")}
          </th>
          <th scope="col" className="py-2 text-right">
            {t("orders.fields.totalAmount")}
          </th>
          <th scope="col" className="py-2">
            {t("orders.list.detailColumn")}
          </th>
        </tr>
      </thead>
      <tbody>
        {items.map((item) => (
          <tr key={item.orderId} className="border-b">
            <td className="py-2">{item.customerOrderCode}</td>
            <td className="py-2">{orderStatusLabel(t, item.status)}</td>
            <td className="py-2 text-right">{t("orders.amount", { value: item.totalAmount })}</td>
            <td className="py-2">
              <Link
                to="/orders/$orderId"
                params={{ orderId: item.orderId }}
                className="underline-offset-4 hover:underline"
              >
                {t("orders.list.showDetail", { code: item.customerOrderCode })}
              </Link>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
