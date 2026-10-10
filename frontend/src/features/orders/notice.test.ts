import { createInstance } from "i18next";
import { describe, expect, it } from "vite-plus/test";

import { ApiProblemError } from "@/api/api-fetch";
import type { ValidationProblem } from "@/api/generated/models";
import ja from "@/i18n/locales/ja.json";

import { failureNotice, formFailure } from "./notice";

// @/i18n は document を使うため、Node のテストでは catalog だけを読んだ instance を作る。
const i18n = createInstance();
await i18n.init({ lng: "ja", resources: { ja: { translation: ja } } });
const { t } = i18n;

const descriptions = { conflict: "競合の説明", unprocessable: "処理できない説明" };
const general = {
  heading: "操作を完了できませんでした",
  description: "時間をおいてもう一度お試しください。",
};

describe("failure notice", () => {
  it("409 は backend の title を見出しに、競合の説明を出す", () => {
    const error = new ApiProblemError(409, { type: "about:blank", title: "Conflict", status: 409 });

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: "Conflict",
      description: "競合の説明",
    });
  });

  it("422 は backend の title を見出しに、処理できない説明を出す", () => {
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

  it("API の problem でない失敗は、一般の見出しと説明を出し、message を出さない", () => {
    expect(failureNotice(t, new Error("internal"), descriptions)).toStrictEqual(general);
  });

  it("problem の本文がない 409 は、一般の見出しに競合の説明を出す", () => {
    const error = new ApiProblemError(409, undefined);

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: general.heading,
      description: "競合の説明",
    });
  });

  it("入力検証の 400 は、入力欄のエラーと入力欄に写せない detail に分ける", () => {
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

  it("400 でも入力検証でない失敗は、一般の見出しと説明を出す", () => {
    const error = new ApiProblemError(400, {
      type: "about:blank",
      title: "Bad Request",
      status: 400,
    });

    expect(formFailure(t, error, descriptions).notice).toStrictEqual(general);
  });
});
