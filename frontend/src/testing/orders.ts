import { screen, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { vi } from "vite-plus/test";

import { getFindOrderByIdMockHandler } from "@/api/generated/mocks/ordering/ordering.msw";
import { getListPaymentsMockHandler } from "@/api/generated/mocks/payment/payment.msw";
import type {
  OrderDetailsResponse,
  OrderSummaryResponse,
  ProductSummaryListResponse,
} from "@/api/generated/models";

import { server } from "./msw";

// 注文の画面のテストが共有する固定の値（生成された MSW handler に渡す）。
export const penId = "11111111-1111-4111-8111-111111111111";
export const notebookId = "22222222-2222-4222-8222-222222222222";
export const eraserId = "33333333-3333-4333-8333-333333333333";
export const orderId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const csrfCookieValue = "4f0e1c2a-5b6d-4e7f-8a9b-0c1d2e3f4a5b";

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

export function stubCsrfCookie(): void {
  vi.spyOn(document, "cookie", "get").mockReturnValue(`__Host-XSRF-TOKEN=${csrfCookieValue}`);
}

/** aria-errormessage が指す要素の文言。誤りがなければ undefined。 */
export function errorMessageOf(element: HTMLElement): string | undefined {
  const id = element.getAttribute("aria-errormessage");
  return id === null
    ? undefined
    : (document.querySelector(`[id="${id}"]`)?.textContent ?? undefined);
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
    http[method](`*/api/orders/:orderId/${path}`, async ({ request }) => {
      bodies.push(await request.json());
      return respond();
    }),
  );
  return bodies;
}

export function noContent() {
  return new HttpResponse(undefined, { status: 204 });
}

async function changeQuantity(user: UserEvent, value: string) {
  const quantity = await screen.findByLabelText("数量");
  await user.clear(quantity);
  await user.type(quantity, value);
}

/** 決済の欄の外の status（ProblemNotice の output）。 */
export function notice() {
  const element = screen
    .getAllByRole("status")
    .find((el) => el.closest('[aria-labelledby="payment-heading"]') === null);
  if (element === undefined) {
    throw new Error("ProblemNotice の status がない");
  }
  return element;
}

/** 最初の詳細の GET だけ成功させ、mutation の後の再取得を 500 にする。決済の参照は既定で 0 件を返す。 */
export function serveDetailFailingRefetch(order: OrderDetailsResponse): void {
  let requests = 0;
  server.use(
    http.get("*/api/orders/:orderId", () => {
      requests += 1;
      return requests === 1
        ? HttpResponse.json(order)
        : problemResponse(500, "Internal Server Error");
    }),
    getListPaymentsMockHandler({ items: [] }),
  );
}

/** 数量を value にして明細の変更を送る。 */
export async function saveQuantity(user: UserEvent, value: string): Promise<void> {
  await changeQuantity(user, value);
  await user.click(screen.getByRole("button", { name: "明細を保存する" }));
}

/** 詳細の明細の表の最初の行（見出しの行の次）。 */
export function firstLineRow(): HTMLElement | undefined {
  const [table] = screen.getAllByRole("table");
  return table === undefined ? undefined : within(table).getAllByRole("row")[1];
}
