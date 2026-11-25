import { ApiProblemError, isIdempotencyKeyInProgress } from "@frontend/api/api-error";
import type { TFunction } from "i18next";

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
  return { heading: title ?? t("notice.generalTitle"), description };
}

function problemStatus(error: unknown): number | undefined {
  return error instanceof ApiProblemError ? error.status : undefined;
}

/**
 * 404 と 409 と 422 は操作ごとの説明を、それ以外は一般の失敗を通知にする。
 * 同じ Idempotency-Key の処理中の 409 は、自動の再試行を使い切った後に届くので、操作によらない説明にする（ADR-126）。
 * 入力検証の 400 は扱わないので、form のある画面では formFailure を使う。
 * conflict は省略できない。409 を呼び出し側で先に扱う場合だけ、明示して undefined を渡す。
 * 404 を操作の失敗として扱う画面だけ、notFound を渡す。
 */
export function failureNotice(
  t: TFunction,
  error: unknown,
  descriptions: { conflict: string | undefined; unprocessable: string; notFound?: string },
): Notice {
  if (isIdempotencyKeyInProgress(error)) {
    return problemNotice(t, error, t("notice.inProgress"));
  }
  const status = problemStatus(error);
  if (status === 404 && descriptions.notFound !== undefined) {
    return problemNotice(t, error, descriptions.notFound);
  }
  if (status === 409 && descriptions.conflict !== undefined) {
    return problemNotice(t, error, descriptions.conflict);
  }
  if (status === 422) {
    return problemNotice(t, error, descriptions.unprocessable);
  }
  // error.message と detail は内部情報を含みうるため出さない。
  return {
    heading: t("notice.generalTitle"),
    description: t("notice.generalFailure"),
  };
}
