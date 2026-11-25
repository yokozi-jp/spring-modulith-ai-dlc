/* @vitest-environment jsdom */

import { getListProductsMockHandler } from "@frontend/api/mocks/product/product.msw";
import type { OrderDetailsResponse } from "@frontend/api/models";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vite-plus/test";

import { server } from "@/testing/msw";
import {
  draftOrder,
  firstLineRow,
  noContent,
  notice,
  orderId,
  penId,
  problemResponse,
  products,
  saveQuantity,
  serveDetail,
  serveDetailFailingRefetch,
  serveUpdate,
  stubCsrfCookie,
} from "@/testing/orders";
import { renderRoute } from "@/testing/render-route";

const detailPath = `/orders/${orderId}`;
const conflictTitle = "ほかの操作が先に更新しました";
const versionConflict = { type: "/problems/version-conflict" };

/** 最初の明細の変更だけを 409 にし、その間に注文を数量 5、lockNo 2 へ進める。 */
function conflictOnce(detail: { order: OrderDetailsResponse }) {
  let conflicted = false;
  return serveUpdate("put", "lines", () => {
    if (conflicted) {
      return noContent();
    }
    conflicted = true;
    detail.order = draftOrder({
      lockNo: 2,
      totalAmount: 600,
      lines: [{ lineNumber: 1, productId: penId, quantity: 5, unitPrice: 120, amount: 600 }],
    });
    return problemResponse(409, conflictTitle, versionConflict);
  });
}

/** 数量を 3 にして送り、409 の通知が出るまで待つ。 */
async function submitConflictingChange() {
  const bodies = conflictOnce(serveDetail(draftOrder()));
  const { user } = await renderRoute(detailPath);
  await saveQuantity(user, "3");
  await expect(within(notice()).findByText(conflictTitle)).resolves.toBeTruthy();
  return { user, bodies };
}

/** 確定と明細の変更の要求の Idempotency-Key を記録し、responses を順に返す（最後の応答を繰り返す）。 */
function serveKeyed(method: "put" | "post", path: string, ...responses: (() => Response)[]) {
  const keys: (string | null)[] = [];
  server.use(
    http[method](`*/api/orders/:orderId/${path}`, ({ request }) => {
      keys.push(request.headers.get("Idempotency-Key"));
      const respond = responses[Math.min(keys.length, responses.length) - 1];
      if (respond === undefined) {
        throw new Error("応答がない");
      }
      return respond();
    }),
  );
  return keys;
}

const inProgressTitle = "同じ Idempotency-Key の要求を処理しています";
const inProgress = { type: "/problems/idempotency-key-in-progress" };

describe("注文の詳細の画面の失敗", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it("明細の変更の 409 は入力を残して最新を表示し、通知を出し、選択肢を live region の外に出す", async () => {
    await submitConflictingChange();

    expect(notice().textContent).toContain("ほかの操作で注文が更新されました。");
    expect(within(notice()).queryByRole("button")).toBeNull();
    expect(screen.getByRole("button", { name: "変更を捨てて最新を表示する" })).toBeTruthy();
    expect(firstLineRow()?.textContent).toContain("5");
    expect(screen.getByLabelText("数量")).toHaveProperty("value", "3");
  });

  it("「捨てる」で form を最新の値にし、通知と選択肢を消す", async () => {
    const { user } = await submitConflictingChange();

    await user.click(screen.getByRole("button", { name: "変更を捨てて最新を表示する" }));

    expect(screen.getByLabelText("数量")).toHaveProperty("value", "5");
    expect(notice().textContent).toBe("");
    expect(screen.queryByRole("button", { name: "最新の版に変更を適用し直す" })).toBeNull();
  });

  it("keyboard で「適用し直す」を選ぶと、今の入力を最新の lockNo で 1 回だけ送る", async () => {
    const { user, bodies } = await submitConflictingChange();
    screen.getByRole("button", { name: "変更を捨てて最新を表示する" }).focus();

    await user.tab();
    expect(screen.getByRole("button", { name: "最新の版に変更を適用し直す" })).toHaveFocus();
    await user.keyboard("{Enter}");

    await expect(within(notice()).findByText("明細を変更しました。")).resolves.toBeTruthy();
    expect(bodies).toStrictEqual([
      { lines: [{ productId: penId, quantity: 3 }], lockNo: 1 },
      { lines: [{ productId: penId, quantity: 3 }], lockNo: 2 },
    ]);
  });

  it("409 の後の取り直しが失敗したら、読み込めなかったことを通知し、選択肢を出さず入力を残す", async () => {
    serveDetailFailingRefetch(draftOrder());
    serveUpdate("put", "lines", () => problemResponse(409, conflictTitle, versionConflict));
    const { user } = await renderRoute(detailPath);

    await saveQuantity(user, "3");

    await expect(within(notice()).findByText(conflictTitle)).resolves.toBeTruthy();
    expect(notice().textContent).toContain("最新の内容を読み込めませんでした。");
    expect(screen.queryByRole("button", { name: "最新の版に変更を適用し直す" })).toBeNull();
    expect(screen.getByLabelText("数量")).toHaveProperty("value", "3");
  });

  it("409 の後の最新が下書きでなければ、変更できないことを通知する", async () => {
    const detail = serveDetail(draftOrder());
    serveUpdate("put", "lines", () => {
      detail.order = draftOrder({ lockNo: 2, status: "CONFIRMED" });
      return problemResponse(409, conflictTitle, versionConflict);
    });
    const { user } = await renderRoute(detailPath);

    await saveQuantity(user, "3");

    await expect(
      within(notice()).findByText("注文が確定または取り消されたため、変更できません。"),
    ).resolves.toBeTruthy();
    expect(screen.queryByLabelText("数量")).toBeNull();
  });

  it("確定の 409 は通知して最新を表示する", async () => {
    const detail = serveDetail(draftOrder());
    serveUpdate("post", "confirm", () => {
      detail.order = draftOrder({ lockNo: 2, status: "CANCELLED" });
      return problemResponse(409, conflictTitle, versionConflict);
    });
    const { user } = await renderRoute(detailPath);

    await user.click(await screen.findByRole("button", { name: "確定する" }));

    await expect(within(notice()).findByText(conflictTitle)).resolves.toBeTruthy();
    expect(notice().textContent).toContain("最新の内容を表示しています。");
    expect(screen.getByText("取消済み")).toBeTruthy();
  });

  it("明細の変更の 422 は理由の title と説明を通知し、入力を残す", async () => {
    serveDetail(draftOrder());
    serveUpdate("put", "lines", () =>
      problemResponse(422, "販売を終了した商品です", {
        type: "/problems/ordering/product-discontinued",
      }),
    );
    const { user } = await renderRoute(detailPath);

    await saveQuantity(user, "3");

    await expect(within(notice()).findByText("販売を終了した商品です")).resolves.toBeTruthy();
    expect(notice().textContent).toContain("販売中の商品かを確かめてください。");
    expect(screen.getByLabelText("数量")).toHaveProperty("value", "3");
  });

  it("problem+json でない 409 は、一般の見出しで通知する", async () => {
    serveDetail(draftOrder());
    serveUpdate("post", "confirm", () => new HttpResponse("conflict", { status: 409 }));
    const { user } = await renderRoute(detailPath);

    await user.click(await screen.findByRole("button", { name: "確定する" }));

    await expect(within(notice()).findByText("操作を完了できませんでした")).resolves.toBeTruthy();
    expect(notice().textContent).toContain("最新の内容を表示しています。");
  });

  it.each([
    {
      name: "確定する",
      path: "confirm",
      status: 422,
      problem: { type: "/problems/ordering/not-draft", title: "下書きではない注文です" },
      text: "この注文は今の状態では操作できません。",
    },
    {
      name: "取り消す",
      path: "cancel",
      status: 500,
      problem: { type: "about:blank", title: "サーバー内部エラー" },
      text: "時間をおいてもう一度お試しください。",
    },
  ] as const)("「$name」の $status は通知する", async ({ name, path, status, problem, text }) => {
    serveDetail(draftOrder());
    serveUpdate("post", path, () => problemResponse(status, problem.title, { type: problem.type }));
    const { user } = await renderRoute(detailPath);

    await user.click(await screen.findByRole("button", { name }));

    await waitFor(() => {
      expect(notice().textContent).toContain(text);
    });
  });
});

describe("注文の詳細の画面の Idempotency-Key の再試行", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it("確定の 503 は同じ Idempotency-Key で自動で再試行し、成功を通知する", async () => {
    serveDetail(draftOrder());
    const keys = serveKeyed(
      "post",
      "confirm",
      () => problemResponse(503, "Service Unavailable"),
      noContent,
    );
    const { user } = await renderRoute(detailPath);

    await user.click(await screen.findByRole("button", { name: "確定する" }));

    await expect(within(notice()).findByText("注文を確定しました。")).resolves.toBeTruthy();
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it("失敗の通知の後に確定を押し直すと、新しい Idempotency-Key で送る", async () => {
    serveDetail(draftOrder());
    const keys = serveKeyed(
      "post",
      "confirm",
      () =>
        problemResponse(422, "下書きではない注文です", { type: "/problems/ordering/not-draft" }),
      noContent,
    );
    const { user } = await renderRoute(detailPath);
    await user.click(await screen.findByRole("button", { name: "確定する" }));
    await expect(within(notice()).findByText("下書きではない注文です")).resolves.toBeTruthy();

    await user.click(screen.getByRole("button", { name: "確定する" }));

    await expect(within(notice()).findByText("注文を確定しました。")).resolves.toBeTruthy();
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("明細の変更の処理中の 409 が続くと、処理中を通知し、競合の選択肢を出さず入力を残す", async () => {
    serveDetail(draftOrder());
    const keys = serveKeyed("put", "lines", () =>
      problemResponse(409, inProgressTitle, inProgress),
    );
    const { user } = await renderRoute(detailPath);

    await saveQuantity(user, "3");

    await expect(within(notice()).findByText(inProgressTitle)).resolves.toBeTruthy();
    expect(notice().textContent).toContain("前の操作をまだ処理しています。");
    expect(screen.queryByRole("button", { name: "変更を捨てて最新を表示する" })).toBeNull();
    expect(screen.getByLabelText("数量")).toHaveProperty("value", "3");
    expect(keys).toStrictEqual([keys[0], keys[0], keys[0], keys[0]]);
  });
});

describe("注文の詳細の画面の決済の失敗", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it("決済の参照が 500 なら決済の欄に失敗と読み直しのボタンを出し、注文の見出しと明細と操作を残す", async () => {
    serveDetail(draftOrder());
    server.use(http.get("*/api/payments", () => problemResponse(500, "Internal Server Error")));

    await renderRoute(detailPath);

    const region = await screen.findByRole("region", { name: "決済" });
    await waitFor(() => {
      expect(within(region).getByRole("status").textContent).toBe(
        "決済の状態を読み込めませんでした。時間をおいて画面を読み直してください。",
      );
    });
    expect(within(region).getByRole("button", { name: "決済の状態を読み直す" })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1, name: "注文 C-001" })).toBeTruthy();
    expect(firstLineRow()?.textContent).toContain("ボールペン");
    expect(screen.getByRole("button", { name: "確定する" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "取り消す" })).toBeTruthy();
  });
});
