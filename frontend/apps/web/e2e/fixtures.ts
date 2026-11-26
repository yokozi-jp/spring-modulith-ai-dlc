import { AxeBuilder } from "@axe-core/playwright";
import { idempotencyKeyHeaders } from "@frontend/api/idempotency-key";
import { expect, test as base } from "@playwright/test";
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

// WCAG 2.2 の AA までの規則と best-practice の規則で検査する（ADR-142）。
const axeTags = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"];

// task e2e は Faro を有効にしてビルドする（ADR-068）が、Collector は起動しないので、/collect を既定で 202 で返す。
// page.route は context.route より優先されるので、spec ごとに上書きできる。
export const test = base.extend<{
  productFactory: (options?: { onSale?: boolean; unitPrice?: number }) => Promise<Product>;
  orderCreatePage: OrderCreatePage;
  orderListPage: OrderListPage;
  orderDetailPage: OrderDetailPage;
  makeAxeBuilder: () => AxeBuilder;
  // 今の画面の screenshot と、axe の結果の全体（incomplete を含む）を report に添付し、違反があれば失敗する。
  expectNoA11yViolations: (name: string) => Promise<void>;
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
  makeAxeBuilder: async ({ page }, provide) => {
    await provide(() => new AxeBuilder({ page }).withTags(axeTags));
  },
  expectNoA11yViolations: async ({ page, makeAxeBuilder }, provide, testInfo) => {
    await provide((name) =>
      base.step(`axe: ${name}`, async () => {
        await testInfo.attach(name, { body: await page.screenshot(), contentType: "image/png" });
        const results = await makeAxeBuilder().analyze();
        await testInfo.attach(`axe-${name}`, {
          body: JSON.stringify(results, undefined, 2),
          contentType: "application/json",
        });
        const violations = results.violations.map(({ id, nodes, helpUrl }) => ({
          id,
          targets: nodes.map(({ target }) => target.join(" ")),
          helpUrl,
        }));
        expect(violations, `${name} の axe の違反`).toEqual([]);
      }),
    );
  },
});
export { expect } from "@playwright/test";
