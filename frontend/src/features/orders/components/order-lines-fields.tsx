import { useTranslation } from "react-i18next";

import type { ProductSummaryResponse } from "@/api/generated/models";
import { Button } from "@/components/ui/button";
import { firstErrorMessage, productOptions, withFieldGroup } from "@/features/orders/form";
import { newLine, quantityMax, quantityMin } from "@/features/orders/schemas";
import type { OrderLinesValues } from "@/features/orders/schemas";
import type { ServerFieldErrors } from "@/features/orders/server-errors";

const inputClassName = "h-8 rounded-md border bg-background px-2 aria-invalid:border-destructive";

interface OrderLinesFieldsProps {
  idPrefix: string;
  products: readonly ProductSummaryResponse[];
  serverErrors: ServerFieldErrors;
  onFieldChange: (name: string) => void;
}

const defaultValues: OrderLinesValues = { lines: [] };
const defaultProps: OrderLinesFieldsProps = {
  idPrefix: "",
  products: [],
  serverErrors: { fields: {}, others: [] },
  // withFieldGroup の props は型を与えるだけで、この関数は呼ばれない。
  // oxlint-disable-next-line eslint/no-empty-function
  onFieldChange: () => {},
};

function invalid(text: string | undefined, id: string) {
  return text === undefined ? {} : { "aria-invalid": true, "aria-errormessage": id };
}

/** 欄の直下の誤りの文言。aria-errormessage が id で指す。 */
function errorText(text: string | undefined, id: string) {
  return text === undefined ? undefined : (
    <p id={id} className="text-sm text-destructive">
      {text}
    </p>
  );
}

/** 作成と明細の変更が共有する明細の入力欄。送信時の検証とサーバーの 400 の両方を欄の直下に出す。 */
export const OrderLinesFields = withFieldGroup({
  defaultValues,
  props: defaultProps,
  // React の component として hook を呼ぶため、名前を大文字で始める。
  // oxlint-disable-next-line eslint/func-name-matching
  render: function OrderLinesFieldsRender({
    group,
    idPrefix,
    products,
    serverErrors,
    onFieldChange,
  }) {
    const { t } = useTranslation();
    const message = (name: string, errors: readonly unknown[]) =>
      firstErrorMessage(errors) ?? serverErrors.fields[name];

    return (
      <group.Field name="lines" mode="array">
        {(linesField) => {
          const linesError = message("lines", linesField.state.meta.errors);
          return (
            <fieldset className="flex flex-col gap-4">
              <legend className="font-semibold">{t("orders.fields.lines")}</legend>
              {errorText(linesError, `${idPrefix}-lines-error`)}
              {linesField.state.value.map((line, index) => {
                const number = index + 1;
                const productName = `lines[${index}].productId` as const;
                const quantityName = `lines[${index}].quantity` as const;
                return (
                  <fieldset
                    key={line.rowKey}
                    className="flex flex-wrap items-end gap-4 rounded-md border p-3"
                  >
                    <legend className="px-1 text-sm">
                      {t("orders.form.lineLegend", { number })}
                    </legend>
                    <group.Field name={productName}>
                      {(field) => {
                        const id = `${idPrefix}-line-${index}-product`;
                        const error = message(productName, field.state.meta.errors);
                        return (
                          <div className="flex flex-col gap-1">
                            <label htmlFor={id}>{t("orders.fields.product")}</label>
                            <select
                              id={id}
                              className={inputClassName}
                              required
                              value={field.state.value}
                              onBlur={field.handleBlur}
                              onChange={(event) => {
                                field.handleChange(event.target.value);
                                onFieldChange(productName);
                              }}
                              {...invalid(error, `${id}-error`)}
                            >
                              <option value="">{t("orders.form.selectProduct")}</option>
                              {productOptions(t, products, line.productId).map((option) => (
                                <option key={option.productId} value={option.productId}>
                                  {option.label}
                                </option>
                              ))}
                            </select>
                            {errorText(error, `${id}-error`)}
                          </div>
                        );
                      }}
                    </group.Field>
                    <group.Field name={quantityName}>
                      {(field) => {
                        const id = `${idPrefix}-line-${index}-quantity`;
                        const error = message(quantityName, field.state.meta.errors);
                        return (
                          <div className="flex flex-col gap-1">
                            <label htmlFor={id}>{t("orders.fields.quantity")}</label>
                            <input
                              id={id}
                              className={inputClassName}
                              type="number"
                              inputMode="numeric"
                              required
                              min={quantityMin}
                              max={quantityMax}
                              step={1}
                              value={field.state.value}
                              onBlur={field.handleBlur}
                              onChange={(event) => {
                                field.handleChange(event.target.value);
                                onFieldChange(quantityName);
                              }}
                              {...invalid(error, `${id}-error`)}
                            />
                            {errorText(error, `${id}-error`)}
                          </div>
                        );
                      }}
                    </group.Field>
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => {
                        linesField.removeValue(index);
                        onFieldChange("lines");
                      }}
                    >
                      {t("orders.form.removeLine", { number })}
                    </Button>
                  </fieldset>
                );
              })}
              <Button
                type="button"
                variant="outline"
                className="self-start"
                onClick={() => {
                  linesField.pushValue(newLine());
                  onFieldChange("lines");
                }}
              >
                {t("orders.form.addLine")}
              </Button>
            </fieldset>
          );
        }}
      </group.Field>
    );
  },
});
