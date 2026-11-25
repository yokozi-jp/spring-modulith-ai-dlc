import { test as base } from "@playwright/test";

import { createProduct } from "./fixture-server.ts";
import type { Product } from "./fixture-server.ts";

// task e2e は Faro を有効にしてビルドする（ADR-068）が、Collector は起動しないので、/collect を既定で 202 で返す。
// page.route は context.route より優先されるので、spec ごとに上書きできる。
// productFactory は、そのテストだけが使う商品を fixture サーバーで作る（ADR-129）。
export const test = base.extend<{
  productFactory: (options?: { onSale?: boolean; unitPrice?: number }) => Promise<Product>;
}>({
  context: async ({ context }, provide) => {
    await context.route("**/collect", (route) => route.fulfill({ status: 202 }));
    await provide(context);
  },
  productFactory: async ({ request }, provide) => {
    await provide((options) => createProduct(request, options));
  },
});
export { expect } from "@playwright/test";
