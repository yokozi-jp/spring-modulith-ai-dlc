import { ApiProblemError } from "@frontend/api/api-error";
import { describe, expect, it } from "vite-plus/test";

import { t } from "@/testing/catalog-i18n";

import { failureNotice } from "./notice";

const descriptions = { conflict: "競合の説明", unprocessable: "処理できない説明" };
const general = {
  heading: "操作を完了できませんでした",
  description: "時間をおいてもう一度お試しください。",
};

describe("操作の失敗の通知", () => {
  it("ほかの操作と競合したら、競合の種類ごとのサーバーの見出しと操作ごとの競合の説明を出す", () => {
    const error = new ApiProblemError(409, {
      type: "/problems/version-conflict",
      title: "ほかの操作が先に更新しました",
      status: 409,
    });

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: "ほかの操作が先に更新しました",
      description: "競合の説明",
    });
  });

  it("業務の規則で受け付けられなかったら、理由ごとのサーバーの見出しと操作ごとの説明を出す", () => {
    const error = new ApiProblemError(422, {
      type: "/problems/ordering/not-draft",
      title: "下書きではない注文です",
      status: 422,
    });

    expect(failureNotice(t, error, descriptions)).toStrictEqual({
      heading: "下書きではない注文です",
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
    const error = new ApiProblemError(409, {
      type: "/problems/version-conflict",
      title: "ほかの操作が先に更新しました",
      status: 409,
    });

    expect(
      failureNotice(t, error, { conflict: undefined, unprocessable: "処理できない説明" }),
    ).toStrictEqual(general);
    // @ts-expect-error conflict の渡し忘れは型エラーにする。
    failureNotice(t, error, { unprocessable: "処理できない説明" });
  });

  it("404 は、notFound を渡した画面だけ操作ごとの説明を出し、渡さなければ一般の見出しと説明を出す", () => {
    const error = new ApiProblemError(404, {
      type: "about:blank",
      title: "Not Found",
      status: 404,
    });

    expect(failureNotice(t, error, { ...descriptions, notFound: "既にない" })).toStrictEqual({
      heading: "Not Found",
      description: "既にない",
    });
    expect(failureNotice(t, error, descriptions)).toStrictEqual(general);
  });
});
