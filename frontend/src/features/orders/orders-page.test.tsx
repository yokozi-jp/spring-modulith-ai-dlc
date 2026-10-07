/* @vitest-environment jsdom */

import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, expectTypeOf, it } from "vite-plus/test";

import { getListOrdersMockHandler } from "@/api/generated/mocks/ordering/ordering.msw";
import type { OrderSummaryListResponse, OrderSummaryResponse } from "@/api/generated/models";
import { server } from "@/testing/msw";
import { orderId, orderSummary } from "@/testing/orders";
import { renderRoute } from "@/testing/render-route";

/** 一覧の要求の status を記録する。status がない要求は "(all)" とする。 */
function serveOrders(items: OrderSummaryResponse[] = [orderSummary()]) {
  const statuses: string[] = [];
  server.use(
    getListOrdersMockHandler(({ request }) => {
      statuses.push(new URL(request.url).searchParams.get("status") ?? "(all)");
      return { items };
    }),
  );
  return statuses;
}

async function rowsOf(items: OrderSummaryResponse[]) {
  serveOrders(items);
  await renderRoute("/orders");
  return within(await screen.findByRole("table")).getAllByRole("row");
}

function currentOf(name: string) {
  return screen.getByRole("link", { name }).getAttribute("aria-current");
}

describe("orders page", () => {
  it("一覧の行に客先注文番号、状態、合計金額、詳細へのリンクを出す", async () => {
    const summary = orderSummary({ lockNo: 2 });
    expectTypeOf(summary.lockNo).toEqualTypeOf<number | undefined>();

    const [, row] = await rowsOf([summary]);

    expect(row?.textContent).toContain("C-001");
    expect(row?.textContent).toContain("下書き");
    expect(row?.textContent).toMatch(/240/u);
    expect(screen.getByRole("link", { name: "C-001 の詳細" }).getAttribute("href")).toBe(
      `/orders/${orderId}`,
    );
  });

  it("対応にない状態はコード値を、欠けた値は「（なし）」を出し、orderId がない行はリンクを出さない", async () => {
    const [, row] = await rowsOf([{ customerOrderCode: "C-002", status: "PAID" }]);

    expect(row?.textContent).toContain("PAID");
    expect(row?.textContent).toContain("（なし）");
    expect(within(row ?? document.body).queryByRole("link")).toBeNull();
  });

  it("keyboard で状態のリンクを選ぶと、その状態で絞り込んだ要求を送り、選択中を示す", async () => {
    const statuses = serveOrders();
    const { user } = await renderRoute("/orders");
    const all = await screen.findByRole("link", { name: "すべて" });
    all.focus();

    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole("link", { name: "下書き" }));
    await user.keyboard("{Enter}");

    await waitFor(() => {
      expect(currentOf("下書き")).toBe("page");
    });
    expect(statuses).toContain("DRAFT");
    expect(currentOf("すべて")).toBeNull();
  });

  it("不正な status は捨てて全件を表示する", async () => {
    const statuses = serveOrders();

    await renderRoute("/orders?status=BOGUS");

    await expect(screen.findByRole("table")).resolves.toBeTruthy();
    expect(statuses).toStrictEqual(["(all)"]);
    expect(currentOf("すべて")).toBe("page");
  });

  it.each<[string, OrderSummaryListResponse]>([
    ["空の items", { items: [] }],
    ["items のない本文", {}],
  ])("0 件（%s）なら空の状態の文言と作成へのリンクを出す", async (_case, body) => {
    server.use(getListOrdersMockHandler(body));

    await renderRoute("/orders", { locale: "en" });

    await expect(screen.findByText("No matching orders.")).resolves.toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.getByRole("link", { name: "Create an order" }).getAttribute("href")).toBe(
      "/orders/new",
    );
  });
});
