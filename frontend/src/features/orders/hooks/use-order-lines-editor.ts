import { useQueryClient } from "@tanstack/react-query";
import type { QueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import {
  getFindOrderByIdQueryKey,
  useChangeOrderLines,
} from "@/api/generated/endpoints/ordering/ordering";
import type { OrderDetailsResponse, OrderLineRequest } from "@/api/generated/models";
import { useAppForm } from "@/features/orders/form";
import { formFailure, problemNotice, problemStatus } from "@/features/orders/notice";
import type { Notice } from "@/features/orders/notice";
import { changeOrderLinesFormSchema } from "@/features/orders/schemas";
import type { OrderLinesValues } from "@/features/orders/schemas";
import { noServerErrors, withoutField } from "@/features/orders/server-errors";
import type { ServerFieldErrors } from "@/features/orders/server-errors";

function linesValues(order: OrderDetailsResponse): OrderLinesValues {
  return {
    lines: order.lines.map((line) => ({
      rowKey: crypto.randomUUID(),
      productId: line.productId,
      quantity: String(line.quantity),
    })),
  };
}

/** 全 query の無効化が終わった後の cache の詳細。再取得が失敗していれば undefined を返す（lockNo を推測しない）。 */
function reloadedOrder(
  queryClient: QueryClient,
  orderId: string,
): OrderDetailsResponse | undefined {
  const queryKey = getFindOrderByIdQueryKey(orderId);
  return queryClient.getQueryState(queryKey)?.status === "success"
    ? queryClient.getQueryData<{ data: OrderDetailsResponse }>(queryKey)?.data
    : undefined;
}

/** 明細の変更の 409 の後の通知と、2 つの選択肢を出すか。 */
function conflictOutcome(
  t: TFunction,
  error: unknown,
  latest: OrderDetailsResponse | undefined,
): { notice: Notice; conflict: boolean } {
  if (latest === undefined) {
    return {
      notice: problemNotice(t, error, t("orders.notice.conflictReloadFailed")),
      conflict: false,
    };
  }
  if (latest.status !== "DRAFT") {
    return { notice: problemNotice(t, error, t("orders.notice.notEditable")), conflict: false };
  }
  return { notice: problemNotice(t, error, t("orders.notice.linesConflict")), conflict: true };
}

interface Base {
  lockNo: number;
  values: OrderLinesValues;
}

interface Feedback {
  conflict: boolean;
  serverErrors: ServerFieldErrors;
}

const submitMeta: { lockNo: number | undefined } = { lockNo: undefined };

/**
 * 明細の変更の form と、409 のときの「捨てる」と「適用し直す」を持つ（docs/frontend/update-conflicts.md）。
 * form は mount のときの詳細と lockNo（base）を一度だけ持ち、query の data が変わっても作り直さない。
 */
export function useOrderLinesEditor(
  orderId: string,
  order: OrderDetailsResponse,
  setNotice: (notice: Notice | undefined) => void,
) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const mutation = useChangeOrderLines();
  // reset した値を defaultValues にも渡し、useForm の options の更新で古い値へ戻らないようにする。
  const [base, setBase] = useState<Base>(() => ({
    lockNo: order.lockNo,
    values: linesValues(order),
  }));
  const [feedback, setFeedback] = useState<Feedback>({
    conflict: false,
    serverErrors: noServerErrors,
  });
  const schema = changeOrderLinesFormSchema(t);

  const send = (
    lines: OrderLineRequest[],
    lockNo: number,
    reset: (values: OrderLinesValues) => void,
  ) => {
    setNotice(undefined);
    setFeedback({ conflict: false, serverErrors: noServerErrors });
    mutation.mutate(
      { orderId, data: { lines, lockNo } },
      {
        // mutate の callback は全 query の再取得の後に呼ばれるので、cache の詳細は最新である（docs/frontend/routing-and-state.md）。
        onSuccess: () => {
          const latest = reloadedOrder(queryClient, orderId);
          if (latest === undefined) {
            setNotice({
              heading: t("orders.notice.linesChanged"),
              description: t("orders.notice.latestNotLoaded"),
            });
            return;
          }
          // 自分の更新の後の版を新しい起点にする。これがないと続けて変更したときに自分の更新に対して 409 になる。
          setBase({ lockNo: latest.lockNo, values: linesValues(latest) });
          reset(linesValues(latest));
          setNotice({ heading: t("orders.notice.linesChanged") });
        },
        onError: (error) => {
          if (problemStatus(error) === 409) {
            const outcome = conflictOutcome(t, error, reloadedOrder(queryClient, orderId));
            setNotice(outcome.notice);
            setFeedback({ conflict: outcome.conflict, serverErrors: noServerErrors });
            return;
          }
          const failure = formFailure(t, error, {
            conflict: t("orders.notice.linesConflict"),
            unprocessable: t("orders.notice.linesUnprocessable"),
          });
          setNotice(failure.notice);
          setFeedback({ conflict: false, serverErrors: failure.serverErrors });
        },
      },
    );
  };

  const form = useAppForm({
    defaultValues: base.values,
    validators: { onSubmit: schema },
    onSubmitMeta: submitMeta,
    onSubmit: ({ value, meta, formApi }) => {
      if (mutation.isPending) {
        return;
      }
      send(schema.parse(value).lines, meta.lockNo ?? base.lockNo, (values) => {
        formApi.reset(values);
      });
    },
  });

  return {
    form,
    conflict: feedback.conflict,
    serverErrors: feedback.serverErrors,
    isPending: mutation.isPending,
    handleFieldChange: (name: string) => {
      setFeedback((current) => ({
        ...current,
        serverErrors: withoutField(current.serverErrors, name),
      }));
    },
    /** 入力を捨て、最新の詳細から form を作り直す。 */
    handleDiscard: () => {
      const latest = reloadedOrder(queryClient, orderId);
      if (latest !== undefined) {
        setBase({ lockNo: latest.lockNo, values: linesValues(latest) });
        form.reset(linesValues(latest));
      }
      setFeedback({ conflict: false, serverErrors: noServerErrors });
      setNotice(undefined);
    },
    /** 今の入力を、最新の lockNo で 1 回だけ送る。また 409 なら同じ流れを繰り返す。 */
    handleReapply: () => {
      // 最新を読めなければ持っている版で送り、古ければ 409 の流れで読み直しの失敗を通知する。
      const lockNo = reloadedOrder(queryClient, orderId)?.lockNo ?? base.lockNo;
      setBase((current) => ({ ...current, lockNo }));
      void form.handleSubmit({ lockNo });
    },
  };
}
