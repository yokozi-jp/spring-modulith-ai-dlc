import { ApiProblemError } from "@frontend/api/api-error";
import type { ValidationProblem } from "@frontend/api/models";
import { describe, expect, it } from "vite-plus/test";

import { t } from "@/testing/catalog-i18n";

import { formFailure } from "./notice";

const descriptions = { conflict: "競合の説明", unprocessable: "処理できない説明" };
const general = {
  heading: "操作を完了できませんでした",
  description: "時間をおいてもう一度お試しください。",
};

describe("注文の入力の失敗の通知", () => {
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
