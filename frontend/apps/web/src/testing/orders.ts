import { getFindOrderByIdMockHandler } from "@frontend/api/mocks/ordering/ordering.msw";
import { getListPaymentsMockHandler } from "@frontend/api/mocks/payment/payment.msw";
import type {
  OrderDetailsResponse,
  OrderSummaryResponse,
  ProductSummaryListResponse,
} from "@frontend/api/models";
import { http, HttpResponse } from "msw";

import { server } from "./msw";

// 注文の画面のテストが共有する固定の値（生成された MSW handler に渡す）。
export const penId = "11111111-1111-4111-8111-111111111111";
export const notebookId = "22222222-2222-4222-8222-222222222222";
export const eraserId = "33333333-3333-4333-8333-333333333333";
export const orderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

export const products: ProductSummaryListResponse = {
  items: [
    {
      productId: penId,
      productCode: "P-001",
      productName: "ボールペン",
      salesStatus: "ON_SALE",
      unitPrice: 120,
    },
    {
      productId: notebookId,
      productCode: "P-002",
      productName: "ノート",
      salesStatus: "ON_SALE",
      unitPrice: 300,
    },
    {
      productId: eraserId,
      productCode: "P-003",
      productName: "消しゴム",
      salesStatus: "DISCONTINUED",
      unitPrice: 80,
    },
  ],
};

export function orderSummary(overrides: Partial<OrderSummaryResponse> = {}): OrderSummaryResponse {
  return {
    orderId,
    customerOrderCode: "C-001",
    status: "DRAFT",
    orderedAt: "2026-10-05T01:02:03.123456Z",
    totalAmount: 240,
    lockNo: 1,
    ...overrides,
  };
}

export function draftOrder(overrides: Partial<OrderDetailsResponse> = {}): OrderDetailsResponse {
  return {
    orderId,
    customerOrderCode: "C-001",
    status: "DRAFT",
    orderedAt: "2026-10-05T01:02:03.123456Z",
    totalAmount: 240,
    lockNo: 1,
    lines: [{ lineNumber: 1, productId: penId, quantity: 2, unitPrice: 120, amount: 240 }],
    ...overrides,
  };
}

/** backend と同じ application/problem+json の失敗の応答。 */
export function problemResponse(
  status: number,
  title: string,
  extra: Record<string, unknown> = {},
) {
  return HttpResponse.json(
    { type: "about:blank", title, status, ...extra },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

/** 詳細の GET が返す注文を、テストの途中で差し替えられるようにする。決済の参照は既定で 0 件を返す。 */
export function serveDetail(initial: OrderDetailsResponse) {
  const state = { order: initial };
  server.use(
    getFindOrderByIdMockHandler(() => state.order),
    getListPaymentsMockHandler({ items: [] }),
  );
  return state;
}

/** 更新系の要求の本文を記録し、respond の応答を返す。 */
export function serveUpdate(method: "put" | "post", path: string, respond: () => Response) {
  const bodies: unknown[] = [];
  server.use(
    http[method](`*/api/v1/orders/:orderId/${path}`, async ({ request }) => {
      bodies.push(await request.json());
      return respond();
    }),
  );
  return bodies;
}

export function noContent() {
  return new HttpResponse(undefined, { status: 204 });
}

/** 最初の詳細の GET だけ成功させ、mutation の後の再取得を 500 にする。決済の参照は既定で 0 件を返す。 */
export function serveDetailFailingRefetch(order: OrderDetailsResponse): void {
  let requests = 0;
  server.use(
    http.get("*/api/v1/orders/:orderId", () => {
      requests += 1;
      return requests === 1
        ? HttpResponse.json(order)
        : problemResponse(500, "Internal Server Error");
    }),
    getListPaymentsMockHandler({ items: [] }),
  );
}
