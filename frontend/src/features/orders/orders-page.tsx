import { useSuspenseQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";

import { getListOrdersSuspenseQueryOptions } from "@/api/generated/endpoints/ordering/ordering";
import { buttonVariants } from "@/components/ui/button";

import { OrderStatusFilter } from "./components/order-status-filter";
import { OrdersTable } from "./components/orders-table";
import { listOrdersParams } from "./order-status";

const routeApi = getRouteApi("/_authenticated/orders/");

export function OrdersPage() {
  const { t } = useTranslation();
  const { status } = routeApi.useLoaderDeps();
  const { data } = useSuspenseQuery(getListOrdersSuspenseQueryOptions(listOrdersParams(status)));
  const { items } = data.data;

  useEffect(() => {
    document.title = t("orders.list.title");
  }, [t]);

  return (
    <section className="flex flex-col gap-6 px-6 py-10 sm:px-10" aria-labelledby="orders-heading">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 id="orders-heading" className="text-2xl font-semibold">
          {t("orders.list.title")}
        </h1>
        <Link to="/orders/new" className={buttonVariants()}>
          {t("orders.list.create")}
        </Link>
      </div>
      <OrderStatusFilter status={status} />
      {items.length === 0 ? (
        <p className="text-muted-foreground">{t("orders.list.empty")}</p>
      ) : (
        <OrdersTable items={items} />
      )}
    </section>
  );
}
