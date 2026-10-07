import { test as base } from "@playwright/test";

// task e2e は Faro を有効にしてビルドする（ADR-068）が、Collector は起動しないので、/collect を既定で 202 で返す。
// page.route は context.route より優先されるので、spec ごとに上書きできる。
export const test = base.extend({
  context: async ({ context }, provide) => {
    await context.route("**/collect", (route) => route.fulfill({ status: 202 }));
    await provide(context);
  },
});
export { expect } from "@playwright/test";
