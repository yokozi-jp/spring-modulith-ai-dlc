import { useTranslation } from "react-i18next";

import { firstErrorMessage, withFieldGroup } from "@/features/orders/form";
import { customerOrderCodeMax } from "@/features/orders/schemas";

const defaultProps: {
  idPrefix: string;
  serverError: string | undefined;
  onFieldChange: (name: string) => void;
} = {
  idPrefix: "",
  serverError: undefined,
  // withFieldGroup の props は型を与えるだけで、この関数は呼ばれない。
  // oxlint-disable-next-line eslint/no-empty-function
  onFieldChange: () => {},
};

/** 作成の客先注文番号の入力欄。409 では重複と断定できないため aria-invalid を付けない。 */
export const CustomerOrderCodeField = withFieldGroup({
  defaultValues: { customerOrderCode: "" },
  props: defaultProps,
  // React の component として hook を呼ぶため、名前を大文字で始める。
  // oxlint-disable-next-line eslint/func-name-matching
  render: function CustomerOrderCodeFieldRender({ group, idPrefix, serverError, onFieldChange }) {
    const { t } = useTranslation();
    const id = `${idPrefix}-customer-order-code`;
    const errorId = `${id}-error`;

    return (
      <group.Field name="customerOrderCode">
        {(field) => {
          const error = firstErrorMessage(field.state.meta.errors) ?? serverError;
          return (
            <div className="flex flex-col gap-1">
              <label htmlFor={id}>{t("orders.fields.customerOrderCode")}</label>
              <input
                id={id}
                className="h-8 max-w-sm rounded-md border bg-background px-2 aria-invalid:border-destructive"
                required
                maxLength={customerOrderCodeMax}
                autoComplete="off"
                value={field.state.value}
                onBlur={field.handleBlur}
                onChange={(event) => {
                  field.handleChange(event.target.value);
                  onFieldChange("customerOrderCode");
                }}
                {...(error === undefined
                  ? {}
                  : { "aria-invalid": true, "aria-errormessage": errorId })}
              />
              {error === undefined ? undefined : (
                <p id={errorId} className="text-sm text-destructive">
                  {error}
                </p>
              )}
            </div>
          );
        }}
      </group.Field>
    );
  },
});
