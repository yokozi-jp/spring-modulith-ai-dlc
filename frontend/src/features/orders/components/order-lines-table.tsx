import { useTranslation } from "react-i18next";

import type { OrderLineResponse, ProductSummaryResponse } from "@/api/generated/models";

/** 詳細の明細。常に最新の query の data から描く。商品名が引けなければ productId をそのまま出す。 */
export function OrderLinesTable({
  lines,
  products,
}: {
  lines: readonly OrderLineResponse[];
  products: readonly ProductSummaryResponse[];
}) {
  const { t } = useTranslation();
  const names = new Map(products.map((product) => [product.productId, product.productName]));

  return (
    <table className="w-full text-left text-sm">
      <thead>
        <tr className="border-b">
          <th scope="col" className="py-2">
            {t("orders.fields.lineNumber")}
          </th>
          <th scope="col" className="py-2">
            {t("orders.fields.product")}
          </th>
          <th scope="col" className="py-2 text-right">
            {t("orders.fields.quantity")}
          </th>
          <th scope="col" className="py-2 text-right">
            {t("orders.fields.unitPrice")}
          </th>
          <th scope="col" className="py-2 text-right">
            {t("orders.fields.amount")}
          </th>
        </tr>
      </thead>
      <tbody>
        {lines.map((line) => (
          <tr key={line.lineNumber} className="border-b">
            <td className="py-2">{line.lineNumber}</td>
            <td className="py-2">{names.get(line.productId) ?? line.productId}</td>
            <td className="py-2 text-right">{t("orders.quantity", { value: line.quantity })}</td>
            <td className="py-2 text-right">{t("orders.amount", { value: line.unitPrice })}</td>
            <td className="py-2 text-right">{t("orders.amount", { value: line.amount })}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
