import type { TFunction } from "i18next";

import { ApiProblemError } from "@/api/api-fetch";
import { noServerErrors, serverFieldErrors } from "@/features/orders/server-errors";
import type { ServerFieldErrors } from "@/features/orders/server-errors";

/** 画面の live region に出す通知。heading は見出し、description は説明の段落。 */
export interface Notice {
  heading: string;
  description?: string;
  /** 入力欄に写せなかったサーバーの検証の detail。 */
  details?: readonly string[];
}

/** backend の title を見出しに、操作ごとの catalog の文を説明にする（docs/frontend/update-conflicts.md）。 */
export function problemNotice(t: TFunction, error: unknown, description: string): Notice {
  const title = error instanceof ApiProblemError ? error.problem?.title : undefined;
  return { heading: title ?? t("orders.notice.generalTitle"), description };
}

export function problemStatus(error: unknown): number | undefined {
  return error instanceof ApiProblemError ? error.status : undefined;
}

/** 409 と 422 は操作ごとの説明を、それ以外は一般の失敗を通知にする。 */
export function failureNotice(
  t: TFunction,
  error: unknown,
  descriptions: { conflict: string; unprocessable: string },
): Notice {
  const status = problemStatus(error);
  if (status === 409) {
    return problemNotice(t, error, descriptions.conflict);
  }
  if (status === 422) {
    return problemNotice(t, error, descriptions.unprocessable);
  }
  // error.message と detail は内部情報を含みうるため出さない。
  return {
    heading: t("orders.notice.generalTitle"),
    description: t("orders.notice.generalFailure"),
  };
}

/** 作成と明細の変更の失敗を、通知と入力欄のサーバーのエラーに分ける。入力は呼び出し側が残す。 */
export function formFailure(
  t: TFunction,
  error: unknown,
  descriptions: { conflict: string; unprocessable: string },
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
