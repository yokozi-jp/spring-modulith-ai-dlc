import { useId } from "react";
import { useTranslation } from "react-i18next";

import type { ProductSummaryResponse } from "@/api/generated/models";
import { Button } from "@/components/ui/button";
import type { useOrderLinesEditor } from "@/features/orders/hooks/use-order-lines-editor";

import { OrderLinesFields } from "./order-lines-fields";

/** 下書きの注文の明細の変更の form。 */
export function OrderLinesEditor({
  editor,
  products,
}: {
  editor: ReturnType<typeof useOrderLinesEditor>;
  products: readonly ProductSummaryResponse[];
}) {
  const { t } = useTranslation();
  const idPrefix = useId();

  return (
    <form
      noValidate
      aria-labelledby={`${idPrefix}-heading`}
      className="flex max-w-3xl flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        void editor.form.handleSubmit();
      }}
    >
      <h2 id={`${idPrefix}-heading`} className="text-xl font-semibold">
        {t("orders.detail.editHeading")}
      </h2>
      <OrderLinesFields
        form={editor.form}
        fields={{ lines: "lines" }}
        idPrefix={idPrefix}
        products={products}
        serverErrors={editor.serverErrors}
        onFieldChange={editor.handleFieldChange}
      />
      <Button type="submit" className="self-start">
        {t("orders.detail.saveLines")}
      </Button>
    </form>
  );
}
