import { idempotencyKeyHeaders } from "@frontend/api/idempotency-key";
import { test as base } from "@playwright/test";
import type { Page } from "@playwright/test";

import { findCookie } from "./environment.ts";

export interface Product {
  productId: string;
  productCode: string;
  productName: string;
  unitPrice: number;
  onSale: boolean;
}

async function createProduct(
  page: Page,
  { onSale = true, unitPrice = 1200 }: { onSale?: boolean; unitPrice?: number } = {},
): Promise<Product> {
  const productCode = `E2E-${crypto.randomUUID().replaceAll("-", "").slice(0, 16)}`;
  const productName = `E2E 商品 ${productCode}`;
  const csrf = await findCookie(page.context(), "__Host-XSRF-TOKEN");
  const response = await page.request.post("/api/products", {
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
      `POST /api/products が ${response.status()} を返しました：${await response.text()}`,
    );
  }
  const productId = location.slice(location.lastIndexOf("/") + 1);
  return { productId, productCode, productName, unitPrice, onSale };
}

// task e2e は Faro を有効にしてビルドする（ADR-068）が、Collector は起動しないので、/collect を既定で 202 で返す。
// page.route は context.route より優先されるので、spec ごとに上書きできる。
export const test = base.extend<{
  productFactory: (options?: { onSale?: boolean; unitPrice?: number }) => Promise<Product>;
}>({
  context: async ({ context }, provide) => {
    await context.route("**/collect", (route) => route.fulfill({ status: 202 }));
    await provide(context);
  },
  productFactory: async ({ page }, provide) => {
    await provide((options) => createProduct(page, options));
  },
});
export { expect } from "@playwright/test";
