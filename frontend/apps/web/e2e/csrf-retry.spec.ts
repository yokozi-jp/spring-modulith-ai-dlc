import { expect, test } from "./fixtures";
import { createOrder, onSaleProduct, uniqueCode } from "./orders";

// apiFetch は CSRF の 403 を受けると、Cookie を読み直して 1 回だけ再送する（api-fetch.ts）。
// ブラウザの Cookie と、403 の Set-Cookie が置く新しい Cookie を実サーバで通して、再送が成功することを確かめる。
test("CSRF の Cookie を失った状態で注文を作成すると、403 の後に 1 回だけ再送して作成できる", async ({
  page,
}) => {
  const product = await onSaleProduct(page.request);
  const statuses: number[] = [];
  page.on("response", (response) => {
    if (
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/orders"
    ) {
      statuses.push(response.status());
    }
  });

  await createOrder(
    page,
    { code: uniqueCode("E2E-CSRF"), productName: product.productName, quantity: 1 },
    // 画面を開いたあとに Cookie だけを失わせ、header なしの 1 通目が 403 になる状態にする。
    async () => {
      await page.context().clearCookies({ name: "__Host-XSRF-TOKEN" });
    },
  );

  expect(statuses).toEqual([403, 201]);
});
