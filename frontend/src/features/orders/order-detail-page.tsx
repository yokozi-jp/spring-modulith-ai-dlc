import { getRouteApi, Link } from "@tanstack/react-router";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";

import { ConflictChoices } from "./components/conflict-choices";
import { OrderLinesEditor } from "./components/order-lines-editor";
import { OrderLinesTable } from "./components/order-lines-table";
import { OrderStatusButtons } from "./components/order-status-buttons";
import { OrderSummary } from "./components/order-summary";
import { ProblemNotice } from "./components/problem-notice";
import { useOrderDetail } from "./hooks/use-order-detail";

const routeApi = getRouteApi("/_authenticated/orders/$orderId/");

export function OrderDetailPage() {
  const { t } = useTranslation();
  const { orderId } = routeApi.useParams();
  const { order, products, editor, actions, notice, editable } = useOrderDetail(orderId);
  const code = order.customerOrderCode ?? t("orders.missingValue");

  useEffect(() => {
    document.title = t("orders.detail.title", { code });
  }, [t, code]);

  return (
    <section
      className="flex flex-col gap-6 px-6 py-10 sm:px-10"
      aria-labelledby="order-detail-heading"
    >
      <Link to="/orders" className="self-start underline-offset-4 hover:underline">
        {t("orders.detail.backToList")}
      </Link>
      <h1 id="order-detail-heading" className="text-2xl font-semibold">
        {t("orders.detail.title", { code })}
      </h1>
      <ProblemNotice notice={notice} />
      {editor.conflict && editable ? (
        <ConflictChoices onDiscard={editor.handleDiscard} onReapply={editor.handleReapply} />
      ) : undefined}
      <OrderSummary order={order} orderId={orderId} />
      <h2 className="text-xl font-semibold">{t("orders.detail.linesHeading")}</h2>
      <OrderLinesTable lines={order.lines ?? []} products={products} />
      {editable ? (
        <>
          <OrderLinesEditor editor={editor} products={products} />
          <OrderStatusButtons onConfirm={actions.handleConfirm} onCancel={actions.handleCancel} />
        </>
      ) : undefined}
    </section>
  );
}
