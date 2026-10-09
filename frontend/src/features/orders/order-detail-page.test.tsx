/* @vitest-environment jsdom */

import { screen, waitFor, within } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

import { getListPaymentsMockHandler } from "@/api/generated/mocks/payment/payment.msw";
import { getListProductsMockHandler } from "@/api/generated/mocks/product/product.msw";
import type { PaymentSummaryResponse } from "@/api/generated/models";
import { server } from "@/testing/msw";
import {
  draftOrder,
  eraserId,
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
const recordedAt = "2026-10-06T01:02:03.123456Z";

/** 注文の決済記録。 */
function payment(overrides: Partial<PaymentSummaryResponse> = {}): PaymentSummaryResponse {
  return {
    paymentId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    orderId,
    amount: 240,
    status: "PAID",
    recordedAt,
    ...overrides,
  };
}

/** 決済の欄（見出し「決済」の region）。 */
function paymentRegion() {
  return screen.findByRole("region", { name: "決済" });
}

/** 決済の欄の状態の文言が text になるまで待つ。 */
async function expectPaymentStatus(text: string) {
  const region = await paymentRegion();
  await waitFor(() => {
    expect(within(region).getByRole("status").textContent).toBe(text);
  });
  return region;
}

describe("order detail page", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it("詳細と明細を表示し、下書きなら明細の変更と確定と取消を出す", async () => {
    serveDetail(draftOrder());

    await renderRoute(detailPath);

    await expect(
      screen.findByRole("heading", { level: 1, name: "注文 C-001" }),
    ).resolves.toBeTruthy();
    expect(firstLineRow()?.textContent).toContain("ボールペン");
    expect(screen.getByLabelText("数量")).toHaveProperty("value", "2");
    expect(screen.getByRole("button", { name: "確定する" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "取り消す" })).toBeTruthy();
  });

  it("既存の明細の販売終了の商品は、状態を付けて選択肢に残す", async () => {
    serveDetail(
      draftOrder({
        lines: [{ lineNumber: 1, productId: eraserId, quantity: 1, unitPrice: 80, amount: 80 }],
      }),
    );

    await renderRoute(detailPath);

    const options = within(await screen.findByLabelText("商品")).getAllByRole("option");
    expect(options.map((option) => option.textContent)).toStrictEqual([
      "商品を選んでください",
      "ボールペン",
      "ノート",
      "消しゴム（販売終了）",
    ]);
  });

  it("引けない商品は productId をそのまま出す", async () => {
    serveDetail(
      draftOrder({
        lines: [
          { lineNumber: 1, productId: "unknown-product", quantity: 1, unitPrice: 80, amount: 80 },
        ],
      }),
    );

    await renderRoute(detailPath);

    await expect(screen.findByText("unknown-product")).resolves.toBeTruthy();
  });

  it.each([404, 400])("詳細が %i なら not found を表示する", async (status) => {
    server.use(http.get("*/api/orders/:orderId", () => problemResponse(status, "Not Found")));

    await renderRoute(detailPath);

    await expect(
      screen.findByRole("heading", { name: "ページが見つかりません" }),
    ).resolves.toBeTruthy();
  });

  it("下書きでなければ更新の操作を出さない", async () => {
    serveDetail(draftOrder({ status: "CONFIRMED" }));

    await renderRoute(detailPath);

    await expect(screen.findByText("確定済み")).resolves.toBeTruthy();
    expect(screen.queryByLabelText("数量")).toBeNull();
    expect(screen.queryByRole("button", { name: "確定する" })).toBeNull();
  });

  it("明細の変更を続けて 2 回送ると、2 回目は 1 回目の後に取り直した lockNo で送る", async () => {
    const detail = serveDetail(draftOrder());
    const bodies = serveUpdate("put", "lines", () => {
      detail.order = draftOrder({ lockNo: detail.order.lockNo + 1 });
      return noContent();
    });
    const { user } = await renderRoute(detailPath);

    await saveQuantity(user, "3");
    await expect(within(notice()).findByText("明細を変更しました。")).resolves.toBeTruthy();
    await saveQuantity(user, "4");

    await waitFor(() => {
      expect(bodies).toHaveLength(2);
    });
    expect(bodies).toStrictEqual([
      { lines: [{ productId: penId, quantity: 3 }], lockNo: 1 },
      { lines: [{ productId: penId, quantity: 4 }], lockNo: 2 },
    ]);
  });

  it("明細の変更の後の取り直しが失敗したら、成功に「最新の内容を読み込めませんでした」を足す", async () => {
    serveDetailFailingRefetch(draftOrder());
    serveUpdate("put", "lines", noContent);
    const { user } = await renderRoute(detailPath);

    await saveQuantity(user, "3");

    await expect(within(notice()).findByText("明細を変更しました。")).resolves.toBeTruthy();
    expect(notice().textContent).toContain("最新の内容を読み込めませんでした。");
  });

  it.each([
    { name: "確定する", path: "confirm", status: "CONFIRMED", message: "注文を確定しました。" },
    { name: "取り消す", path: "cancel", status: "CANCELLED", message: "注文を取り消しました。" },
  ] as const)(
    "「$name」は表示中の lockNo を送り、成功を通知する",
    async ({ name, path, status, message }) => {
      const detail = serveDetail(draftOrder({ lockNo: 7 }));
      const bodies = serveUpdate("post", path, () => {
        detail.order = draftOrder({ lockNo: 8, status });
        return noContent();
      });
      const { user } = await renderRoute(detailPath);

      await user.click(await screen.findByRole("button", { name }));

      await expect(within(notice()).findByText(message)).resolves.toBeTruthy();
      expect(bodies).toStrictEqual([{ lockNo: 7 }]);
      expect(screen.queryByRole("button", { name })).toBeNull();
    },
  );

  it.each([
    { name: "確定する", path: "confirm", message: "注文を確定しました。" },
    { name: "取り消す", path: "cancel", message: "注文を取り消しました。" },
  ] as const)("「$name」の送信中は 2 回目を送らない", async ({ name, path, message }) => {
    let release = false;
    serveDetail(draftOrder());
    const bodies = serveUpdate("post", path, noContent);
    server.use(
      http.post(
        `*/api/orders/:orderId/${path}`,
        async () => {
          await vi.waitUntil(() => release);
        },
        { once: true },
      ),
    );
    const { user } = await renderRoute(detailPath);
    await user.click(await screen.findByRole("button", { name }));

    await user.click(screen.getByRole("button", { name }));
    release = true;

    await expect(within(notice()).findByText(message)).resolves.toBeTruthy();
    expect(bodies).toHaveLength(1);
  });
});

describe("order detail page payment status", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it.each([
    ["PAID", "決済済み"],
    ["DECLINED", "決済代行が決済を拒否しました。"],
    ["FAILED", "決済に失敗しました。再投入では回復しないため、管理者に連絡してください。"],
  ])(
    "決済記録が %s なら、その結果と金額と記録した時刻を出し、time に recordedAt を入れる",
    async (status, message) => {
      serveDetail(draftOrder({ status: "CONFIRMED" }));
      server.use(getListPaymentsMockHandler({ items: [payment({ status })] }));

      await renderRoute(detailPath);

      const region = await expectPaymentStatus(message);
      expect(within(region).getByText("請求した金額").nextElementSibling?.textContent).toBe(
        "￥240",
      );
      const time = within(region).getByText(
        new Intl.DateTimeFormat("ja", { dateStyle: "medium", timeStyle: "medium" }).format(
          Temporal.Instant.from(recordedAt).epochMilliseconds,
        ),
      );
      expect(time.tagName).toBe("TIME");
      expect(time.getAttribute("datetime")).toBe(recordedAt);
    },
  );

  it("決済記録の状態が知らない値なら、決済済みとせず不明を出す", async () => {
    serveDetail(draftOrder({ status: "CONFIRMED" }));
    server.use(getListPaymentsMockHandler({ items: [payment({ status: "REFUNDED" })] }));

    await renderRoute(detailPath);

    const region = await expectPaymentStatus(
      "決済の状態が分かりません。管理者に連絡してください。",
    );
    expect(within(region).getByText("請求した金額")).toBeTruthy();
  });

  it("確定済みで決済記録がなければ、まだ受け付けていないことを出し、記録した時刻を出さない", async () => {
    serveDetail(draftOrder({ status: "CONFIRMED" }));

    await renderRoute(detailPath);

    const region = await expectPaymentStatus(
      "決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。",
    );
    expect(within(region).queryByText("結果を記録した時刻")).toBeNull();
  });

  it.each(["DRAFT", "CANCELLED"])(
    "%s で決済記録がなければ、決済の対象ではないことを出す",
    async (status) => {
      serveDetail(draftOrder({ status }));

      await renderRoute(detailPath);

      const region = await expectPaymentStatus("この注文は決済の対象ではありません。");
      expect(within(region).queryByText("結果を記録した時刻")).toBeNull();
    },
  );
});
