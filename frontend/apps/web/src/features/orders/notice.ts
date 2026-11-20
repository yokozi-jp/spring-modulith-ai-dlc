import type { OrderDetailsResponse } from "@frontend/api/models";
import type { TFunction } from "i18next";

import { noServerErrors, serverFieldErrors } from "@/features/orders/server-errors";
import type { ServerFieldErrors } from "@/features/orders/server-errors";
import { failureNotice, problemNotice } from "@/lib/notice";
import type { Notice } from "@/lib/notice";

/** 作成と明細の変更の失敗を、通知と入力欄のサーバーのエラーに分ける。入力は呼び出し側が残す。 */
export function formFailure(
  t: TFunction,
  error: unknown,
  descriptions: { conflict: string | undefined; unprocessable: string },
): { notice: Notice; serverErrors: ServerFieldErrors } {
  const fieldErrors = serverFieldErrors(error);
  if (fieldErrors !== undefined) {
    return {
      notice: {
        ...problemNotice(t, error, t("orders.notice.validation")),
        details: fieldErrors.others,
      },
      serverErrors: fieldErrors,
    };
  }
  return { notice: failureNotice(t, error, descriptions), serverErrors: noServerErrors };
}

/** 明細の変更の 409 の後の通知と、2 つの選択肢を出すか。 */
export function linesConflictOutcome(
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
