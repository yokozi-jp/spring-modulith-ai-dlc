import { useSuspenseQuery } from "@tanstack/react-query";
import { useEffect, useId } from "react";
import { useTranslation } from "react-i18next";

import { getListProductsSuspenseQueryOptions } from "@/api/generated/endpoints/product/product";
import { Button } from "@/components/ui/button";

import { CustomerOrderCodeField } from "./components/customer-order-code-field";
import { OrderLinesFields } from "./components/order-lines-fields";
import { ProblemNotice } from "./components/problem-notice";
import { useDraftOrderForm } from "./hooks/use-draft-order-form";

export function OrderNewPage() {
  const { t } = useTranslation();
  const idPrefix = useId();
  const { data: products } = useSuspenseQuery(getListProductsSuspenseQueryOptions());
  const { form, notice, serverErrors, handleFieldChange } = useDraftOrderForm();

  useEffect(() => {
    document.title = t("orders.new.title");
  }, [t]);

  return (
    <section
      className="flex flex-col gap-6 px-6 py-10 sm:px-10"
      aria-labelledby="order-new-heading"
    >
      <h1 id="order-new-heading" className="text-2xl font-semibold">
        {t("orders.new.title")}
      </h1>
      <ProblemNotice notice={notice} />
      <form
        noValidate
        className="flex max-w-3xl flex-col gap-6"
        onSubmit={(event) => {
          event.preventDefault();
          void form.handleSubmit();
        }}
      >
        <CustomerOrderCodeField
          form={form}
          fields={{ customerOrderCode: "customerOrderCode" }}
          idPrefix={idPrefix}
          serverError={serverErrors.fields.customerOrderCode}
          onFieldChange={handleFieldChange}
        />
        <OrderLinesFields
          form={form}
          fields={{ lines: "lines" }}
          idPrefix={idPrefix}
          products={products.data.items ?? []}
          serverErrors={serverErrors}
          onFieldChange={handleFieldChange}
        />
        <Button type="submit" className="self-start">
          {t("orders.new.submit")}
        </Button>
      </form>
    </section>
  );
}
