import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { orderStatuses, orderStatusLabel } from "@/features/orders/order-status";
import type { OrderStatus } from "@/features/orders/order-status";

const linkClassName =
  "rounded-md px-2 py-1 underline-offset-4 hover:underline aria-[current=page]:bg-muted aria-[current=page]:font-semibold";

/** 状態の絞り込み。選択中のリンクだけに aria-current="page" を付ける。 */
export function OrderStatusFilter({ status }: { status: OrderStatus | undefined }) {
  const { t } = useTranslation();

  return (
    <nav aria-label={t("orders.list.filterLabel")}>
      <ul className="flex flex-wrap gap-2">
        <li>
          <Link
            to="/orders"
            // Router は search の部分一致で active を決めるため、status がないときだけ active にする。
            search={{ status: undefined }}
            activeOptions={{ explicitUndefined: true }}
            className={linkClassName}
            aria-current={status === undefined ? "page" : undefined}
          >
            {t("orders.list.filterAll")}
          </Link>
        </li>
        {orderStatuses.map((value) => (
          <li key={value}>
            <Link
              to="/orders"
              search={{ status: value }}
              className={linkClassName}
              aria-current={status === value ? "page" : undefined}
            >
              {orderStatusLabel(t, value)}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
