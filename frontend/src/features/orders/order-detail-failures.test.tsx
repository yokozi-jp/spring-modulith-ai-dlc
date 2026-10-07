/* @vitest-environment jsdom */

import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vite-plus/test";

import { getListProductsMockHandler } from "@/api/generated/mocks/product/product.msw";
import type { OrderDetailsResponse } from "@/api/generated/models";
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
const conflictTitle = "競合が発生しました";

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
    return problemResponse(409, conflictTitle);
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

describe("order detail page failures", () => {
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
    expect(document.activeElement).toBe(
      screen.getByRole("button", { name: "最新の版に変更を適用し直す" }),
    );
    await user.keyboard("{Enter}");

    await expect(within(notice()).findByText("明細を変更しました。")).resolves.toBeTruthy();
    expect(bodies).toStrictEqual([
      { lines: [{ productId: penId, quantity: 3 }], lockNo: 1 },
      { lines: [{ productId: penId, quantity: 3 }], lockNo: 2 },
    ]);
  });

  it("409 の後の取り直しが失敗したら、読み込めなかったことを通知し、選択肢を出さず入力を残す", async () => {
    serveDetailFailingRefetch(draftOrder());
    serveUpdate("put", "lines", () => problemResponse(409, conflictTitle));
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
      return problemResponse(409, conflictTitle);
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
      return problemResponse(409, conflictTitle);
    });
    const { user } = await renderRoute(detailPath);

    await user.click(await screen.findByRole("button", { name: "確定する" }));

    await expect(within(notice()).findByText(conflictTitle)).resolves.toBeTruthy();
    expect(notice().textContent).toContain("最新の内容を表示しています。");
    expect(screen.getByText("取消済み")).toBeTruthy();
  });

  it("明細の変更の 422 は title と説明を通知し、入力を残す", async () => {
    serveDetail(draftOrder());
    serveUpdate("put", "lines", () => problemResponse(422, "処理できない内容です"));
    const { user } = await renderRoute(detailPath);

    await saveQuantity(user, "3");

    await expect(within(notice()).findByText("処理できない内容です")).resolves.toBeTruthy();
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
      text: "この注文は今の状態では操作できません。",
    },
    { name: "取り消す", path: "cancel", status: 500, text: "時間をおいてもう一度お試しください。" },
  ] as const)("「$name」の $status は通知する", async ({ name, path, status, text }) => {
    serveDetail(draftOrder());
    serveUpdate("post", path, () => problemResponse(status, "失敗しました"));
    const { user } = await renderRoute(detailPath);

    await user.click(await screen.findByRole("button", { name }));

    await waitFor(() => {
      expect(notice().textContent).toContain(text);
    });
  });
});

describe("order detail page payment failures", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it("決済の参照が 500 なら決済の欄に失敗を出し、注文の見出しと明細と操作を残す", async () => {
    serveDetail(draftOrder());
    server.use(http.get("*/api/payments", () => problemResponse(500, "Internal Server Error")));

    await renderRoute(detailPath);

    const region = await screen.findByRole("region", { name: "決済" });
    await waitFor(() => {
      expect(within(region).getByRole("status").textContent).toBe(
        "決済の状態を読み込めませんでした。時間をおいて画面を読み直してください。",
      );
    });
    expect(within(region).queryByText("決済した時刻")).toBeNull();
    expect(screen.getByRole("heading", { level: 1, name: "注文 C-001" })).toBeTruthy();
    expect(firstLineRow()?.textContent).toContain("ボールペン");
    expect(screen.getByRole("button", { name: "確定する" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "取り消す" })).toBeTruthy();
  });
});
