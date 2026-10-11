import { describe, expect, it } from "vite-plus/test";

import { ApiProblemError } from "@/api/api-fetch";
import type { ValidationProblem } from "@/api/generated/models";
import { t } from "@/testing/catalog-i18n";

import { failureNotice, formFailure } from "./notice";

const descriptions = { conflict: "競合の説明", unprocessable: "処理できない説明" };
const general = {
  heading: "操作を完了できませんでした",
  description: "時間をおいてもう一度お試しください。",
};

describe("注文操作の失敗の通知", () => {
  it("ほかの操作と競合したら、サーバーの見出しと操作ごとの競合の説明を出す", () => {
    const error = new ApiProblemError(409, { type: "about:blank", title: "Conflict", status: 409 });

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: "Conflict",
      description: "競合の説明",
    });
  });

  it("業務の規則で受け付けられなかったら、サーバーの見出しと操作ごとの説明を出す", () => {
    const error = new ApiProblemError(422, {
      type: "about:blank",
      title: "Unprocessable",
      status: 422,
    });

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: "Unprocessable",
      description: "処理できない説明",
    });
  });

  it("サーバーの応答でない失敗は、一般の見出しと説明だけを出し、エラーの文を出さない", () => {
    expect(failureNotice(t, new Error("internal"), descriptions)).toStrictEqual(general);
  });

  it("サーバーの内部の失敗は、サーバーが返した見出しと詳細を出さず、一般の見出しと説明だけを出す", () => {
    const error = new ApiProblemError(500, {
      type: "about:blank",
      title: "Internal Server Error",
      status: 500,
      detail: "SQLException: relation orders",
    });

    expect(failureNotice(t, error, descriptions)).toStrictEqual(general);
  });

  it("サーバーの見出しがない競合は、一般の見出しに競合の説明を出す", () => {
    const error = new ApiProblemError(409, undefined);

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: general.heading,
      description: "競合の説明",
    });
  });

  it("サーバーの見出しがない業務の規則の失敗は、一般の見出しに操作ごとの説明を出す", () => {
    const error = new ApiProblemError(422, undefined);

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: general.heading,
      description: "処理できない説明",
    });
  });

  it("競合を画面が自分で扱う場合に競合したら、一般の見出しと説明を出す", () => {
    const error = new ApiProblemError(409, { type: "about:blank", title: "Conflict", status: 409 });

    expect(
      failureNotice(t, error, { conflict: undefined, unprocessable: "処理できない説明" }),
    ).toStrictEqual(general);
    // @ts-expect-error conflict の渡し忘れは型エラーにする。
    failureNotice(t, error, { unprocessable: "処理できない説明" });
  });

  it("入力の誤りは、入力欄ごとのエラーと、入力欄に結びつかない説明に分ける", () => {
    const problem: ValidationProblem = {
      type: "/problems/validation-error",
      title: "Bad Request",
      status: 400,
      errors: [
        { pointer: "/customerOrderCode", detail: "必須です" },
        { pointer: "/unknown", detail: "その他" },
      ],
    };
    const error = new ApiProblemError(400, problem);

    expect(formFailure(t, error, descriptions)).toStrictEqual({
      notice: {
        heading: "Bad Request",
        description: "入力を確かめてください。",
        details: ["その他"],
      },
      serverErrors: { fields: { customerOrderCode: "必須です" }, others: ["その他"] },
    });
  });

  it("入力の誤り以外で受け付けられなかったら、一般の見出しと説明を出す", () => {
    const error = new ApiProblemError(400, {
      type: "about:blank",
      title: "Bad Request",
      status: 400,
    });

    expect(formFailure(t, error, descriptions).notice).toStrictEqual(general);
  });
});
