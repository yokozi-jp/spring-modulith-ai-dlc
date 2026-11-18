import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import { useDraftOrder } from "@/api/generated/endpoints/ordering/ordering";
import { useAppForm } from "@/features/orders/form";
import { formFailure } from "@/features/orders/notice";
import type { Notice } from "@/features/orders/notice";
import { draftOrderFormSchema, newLine } from "@/features/orders/schemas";
import type { DraftOrderValues } from "@/features/orders/schemas";
import { noServerErrors, withoutField } from "@/features/orders/server-errors";

// Location は絶対 URI（ServletUriComponentsBuilder）で返るため、URL として解決し、同じ origin のときだけ path から読む。
function createdOrderId(location: string | null): string | undefined {
  if (location === null) {
    return undefined;
  }
  const url = URL.parse(location, globalThis.location.origin);
  if (url === null || url.origin !== globalThis.location.origin) {
    return undefined;
  }
  return /^\/api\/orders\/(?<orderId>[^/]+)$/u.exec(url.pathname)?.groups?.orderId;
}

// module の読み込みで 1 回だけ作り、描画のたびに明細の rowKey を作り直さない。
const defaultValues: DraftOrderValues = { customerOrderCode: "", lines: [newLine()] };

/** 作成の form。409 と 422 では入力を残して通知し、成功したら作った注文の詳細へ遷移する。 */
export function useDraftOrderForm() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const mutation = useDraftOrder();
  const [notice, setNotice] = useState<Notice>();
  const [serverErrors, setServerErrors] = useState(noServerErrors);
  const schema = draftOrderFormSchema(t);
  const form = useAppForm({
    defaultValues,
    validators: { onSubmit: schema },
    onSubmit: ({ value }) => {
      // 送信中の 2 回目は送らない。button は disabled にせず、送信中を通知で示す。
      if (mutation.isPending) {
        return;
      }
      setNotice(undefined);
      mutation.mutate(
        { data: schema.parse(value) },
        {
          onSuccess: (response) => {
            const orderId = createdOrderId(response.headers.get("Location"));
            void (orderId === undefined
              ? navigate({ to: "/orders" })
              : navigate({ to: "/orders/$orderId", params: { orderId } }));
          },
          onError: (error) => {
            const failure = formFailure(t, error, {
              conflict: t("orders.notice.createConflict"),
              unprocessable: t("orders.notice.createUnprocessable"),
            });
            setNotice(failure.notice);
            setServerErrors(failure.serverErrors);
          },
        },
      );
    },
  });

  return {
    form,
    serverErrors,
    notice: mutation.isPending ? { heading: t("orders.form.submitting") } : notice,
    handleFieldChange: (name: string) => {
      setServerErrors((errors) => withoutField(errors, name));
    },
  };
}
