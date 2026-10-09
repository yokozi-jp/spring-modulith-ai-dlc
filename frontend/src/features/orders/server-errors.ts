import { ApiProblemError } from "@/api/api-fetch";
import type { ValidationProblem } from "@/api/generated/models";

/** 400 の errors を、入力欄の名前ごとの detail と、入力欄に写せない detail に分けたもの。 */
export interface ServerFieldErrors {
  fields: Partial<Record<string, string>>;
  others: string[];
}

export const noServerErrors: ServerFieldErrors = { fields: {}, others: [] };

/** RFC 6901 の pointer を TanStack Form の field の名前へ写す。写せなければ undefined。 */
function fieldNameOf(pointer: string): string | undefined {
  if (pointer === "/customerOrderCode" || pointer === "/lines") {
    return pointer.slice(1);
  }
  const line = /^\/lines\/(?<index>\d+)\/(?<field>productId|quantity)$/u.exec(pointer)?.groups;
  return line === undefined ? undefined : `lines[${line.index}].${line.field}`;
}

/** 入力検証の 400 なら errors を入力欄へ振り分ける。それ以外の失敗は undefined を返す。 */
export function serverFieldErrors(error: unknown): ServerFieldErrors | undefined {
  if (
    !(error instanceof ApiProblemError) ||
    error.status !== 400 ||
    error.problem?.type !== "/problems/validation-error"
  ) {
    return undefined;
  }
  // 同一オリジンのバックエンドの形は契約テストが保証するため、Zod で検証しない（docs/frontend/api-client-orval.md）。
  const { errors = [] } = error.problem as ValidationProblem;
  const result: ServerFieldErrors = { fields: {}, others: [] };
  for (const { pointer, detail } of errors) {
    const name = fieldNameOf(pointer);
    if (name === undefined || result.fields[name] !== undefined) {
      result.others.push(detail);
    } else {
      result.fields[name] = detail;
    }
  }
  return result;
}

/** 入力欄が変わったら、その欄のサーバーのエラーだけを消す。明細の追加と削除では行の位置がずれるため、明細のエラーをすべて消す。 */
export function withoutField(errors: ServerFieldErrors, name: string): ServerFieldErrors {
  const removed = (key: string) => key === name || (name === "lines" && key.startsWith("lines"));
  const keys = Object.keys(errors.fields);
  if (!keys.some((key) => removed(key))) {
    return errors;
  }
  const fields = Object.fromEntries(Object.entries(errors.fields).filter(([key]) => !removed(key)));
  return { fields, others: errors.others };
}
