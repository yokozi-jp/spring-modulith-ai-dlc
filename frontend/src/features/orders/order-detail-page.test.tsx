/* @vitest-environment jsdom */

import { screen, waitFor, within } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

import { getListProductsMockHandler } from "@/api/generated/mocks/product/product.msw";
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

  it("明細の欠けた値は「（なし）」を、引けない商品は productId をそのまま出す", async () => {
    serveDetail(draftOrder({ lines: [{ productId: "unknown-product" }] }));

    await renderRoute(detailPath);

    await expect(screen.findByText("unknown-product")).resolves.toBeTruthy();
    expect(firstLineRow()?.textContent?.match(/（なし）/gu)).toHaveLength(4);
  });

  it.each([404, 400])("詳細が %i なら not found を表示する", async (status) => {
    server.use(http.get("*/api/orders/:orderId", () => problemResponse(status, "Not Found")));

    await renderRoute(detailPath);

    await expect(
      screen.findByRole("heading", { name: "ページが見つかりません" }),
    ).resolves.toBeTruthy();
  });

  it.each([
    { label: "確定済み", order: draftOrder({ status: "CONFIRMED" }) },
    { label: "下書き", order: (({ lockNo: _lockNo, ...unlocked }) => unlocked)(draftOrder()) },
    { label: "下書き", order: (({ orderId: _orderId, ...anonymous }) => anonymous)(draftOrder()) },
  ])("下書きでないか lockNo がなければ更新の操作を出さない（$label）", async ({ label, order }) => {
    serveDetail(order);

    await renderRoute(detailPath);

    await expect(screen.findByText(label)).resolves.toBeTruthy();
    expect(screen.queryByLabelText("数量")).toBeNull();
    expect(screen.queryByRole("button", { name: "確定する" })).toBeNull();
  });

  it("明細の変更を続けて 2 回送ると、2 回目は 1 回目の後に取り直した lockNo で送る", async () => {
    const detail = serveDetail(draftOrder());
    const bodies = serveUpdate("put", "lines", () => {
      detail.order = draftOrder({ lockNo: (detail.order.lockNo ?? 0) + 1 });
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
