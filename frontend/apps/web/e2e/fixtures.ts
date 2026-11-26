import { idempotencyKeyHeaders } from "@frontend/api/idempotency-key";
import { test as base } from "@playwright/test";
import type { Page } from "@playwright/test";

import { findCookie } from "./environment.ts";
import { OrderCreatePage } from "./order-create-page.ts";
import { OrderDetailPage } from "./order-detail-page.ts";
import { OrderListPage } from "./order-list-page.ts";

export interface Product {
  productId: string;
  productCode: string;
  productName: string;
  unitPrice: number;
  onSale: boolean;
}

// 並列のテストと衝突しない客先注文番号。上限の 30 文字に収める。
export function uniqueCode(prefix: string): string {
  return `${prefix}-${crypto.randomUUID().slice(0, 18)}`;
}

async function createProduct(
  page: Page,
  { onSale = true, unitPrice = 1200 }: { onSale?: boolean; unitPrice?: number } = {},
): Promise<Product> {
  const productCode = `E2E-${crypto.randomUUID().replaceAll("-", "").slice(0, 16)}`;
  const productName = `E2E 商品 ${productCode}`;
  const csrf = await findCookie(page.context(), "__Host-XSRF-TOKEN");
  const response = await page.request.post("/api/v1/products", {
    headers: { "X-XSRF-TOKEN": csrf.value, ...idempotencyKeyHeaders() },
    data: {
      productCode,
      productName,
      unitPrice,
      salesStatus: onSale ? "ON_SALE" : "DISCONTINUED",
    },
  });
  const { location } = response.headers();
  if (response.status() !== 201 || location === undefined) {
    throw new Error(
      `POST /api/v1/products が ${response.status()} を返しました：${await response.text()}`,
    );
  }
  const productId = location.slice(location.lastIndexOf("/") + 1);
  return { productId, productCode, productName, unitPrice, onSale };
}

// task e2e は Faro を有効にしてビルドする（ADR-068）が、Collector は起動しないので、/collect を既定で 202 で返す。
// page.route は context.route より優先されるので、spec ごとに上書きできる。
export const test = base.extend<{
  productFactory: (options?: { onSale?: boolean; unitPrice?: number }) => Promise<Product>;
  orderCreatePage: OrderCreatePage;
  orderListPage: OrderListPage;
  orderDetailPage: OrderDetailPage;
}>({
  context: async ({ context }, provide) => {
    await context.route("**/collect", (route) => route.fulfill({ status: 202 }));
    await provide(context);
  },
  productFactory: async ({ page }, provide) => {
    await provide((options) => createProduct(page, options));
  },
  orderCreatePage: async ({ page }, provide) => {
    await provide(new OrderCreatePage(page));
  },
  orderListPage: async ({ page }, provide) => {
    await provide(new OrderListPage(page));
  },
  orderDetailPage: async ({ page }, provide) => {
    await provide(new OrderDetailPage(page));
  },
});
export { expect } from "@playwright/test";
