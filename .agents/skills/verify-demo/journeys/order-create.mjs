// 作成の画面の入力の誤り、明細の追加と削除、販売中の商品だけの選択肢、重複、CSRF、下書きの作成を確かめる。
// features/order-create.md の `create-validation`、`create-lines`、`create-on-sale-only`、`create-duplicate`、`create-csrf`、`create-draft`。
export default async function ({ page, expect, step, sql, helpers, screensOf, record }) {
  const { list, create } = screensOf(page);
  const products = await helpers.listProducts(page.request);
  const onSale = products.filter((item) => item.salesStatus === "ON_SALE");
  const discontinued = products.find((item) => item.salesStatus === "DISCONTINUED");
  if (onSale.length < 2 || discontinued === undefined) {
    throw new Error("販売中の商品 2 つと販売終了の商品 1 つが要ります。stack.sh up のシーダーを確かめてください。");
  }

  await list.goto();
  await list.createLink.click();
  await expect(create.heading).toBeVisible();

  // create-on-sale-only
  const select = create.productSelect;
  await expect(select.getByRole("option", { name: discontinued.productName })).toHaveCount(0);
  await expect(select.getByRole("option", { name: onSale[0].productName })).toHaveCount(1);

  // create-validation
  const posts = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && new URL(request.url()).pathname === "/api/v1/orders") posts.push(request.url());
  });
  await create.submitButton.click();
  await expect(page.getByText("客先注文番号を入力してください。")).toBeVisible();
  await expect(page.getByText("商品を選んでください。", { exact: true })).toBeVisible();
  // 誤りの残るフォームは送信の検証を走らせ直さないので、範囲外の数量は開き直した画面で確かめる（features の Gotchas）。
  await create.goto();
  await create.fill({ code: helpers.uniqueCode("VERIFY"), productName: onSale[0].productName, quantity: 0 });
  await create.submitButton.click();
  await expect(page.getByText(/^数量は .* 以上 .* 以下の整数にしてください。$/u)).toBeVisible();
  await expect(page).toHaveURL(/\/orders\/new$/u);
  const validationPosts = [...posts];
  expect(validationPosts, "入力の誤りでは POST /api/v1/orders を送らない").toEqual([]);
  await step("validation");

  // create-duplicate
  await create.fill({ code: "SEED-C01", productName: onSale[0].productName, quantity: 1 });
  await create.submitButton.click();
  await expect(page.getByText(/この客先注文番号は既に使われている可能性があります。/u)).toBeVisible();
  await expect(page).toHaveURL(/\/orders\/new$/u);
  expect(sql("select count(*) from ordering.t_order where customer_order_code = 'SEED-C01'")).toBe("1");
  await step("duplicate");

  // create-lines と create-draft
  const code = helpers.uniqueCode("VERIFY");
  await create.goto();
  await create.customerOrderCodeInput.fill(code);
  await create.line(1).getByLabel("商品").selectOption({ label: onSale[0].productName });
  await create.line(1).getByLabel("数量").fill("1");
  await create.addLineButton.click();
  await expect(create.line(2)).toBeVisible();
  await create.removeLineButton(2).click();
  await expect(create.line(2)).toHaveCount(0);
  await create.addLineButton.click();
  await create.line(2).getByLabel("商品").selectOption({ label: onSale[1].productName });
  await create.line(2).getByLabel("数量").fill("2");
  await step("two-lines");
  await create.submitButton.click();
  await page.waitForURL(/\/orders\/[0-9a-f-]{36}$/u);
  const orderId = new URL(page.url()).pathname.slice("/orders/".length);
  const { detail } = screensOf(page);
  await expect(detail.heading(code)).toBeVisible();
  await expect(detail.lineRows).toHaveCount(3);
  await step("draft");
  const status = sql("select order_status_typ from ordering.t_order where public_id = :'orderId'", { orderId });
  expect(status).toBe("DRAFT");

  // create-csrf
  const csrfCode = helpers.uniqueCode("VERIFY");
  const rejected = await page.request.post("/api/v1/orders", {
    data: { customerOrderCode: csrfCode, lines: [{ productId: onSale[0].productId, quantity: 1 }] },
  });
  expect(rejected.status(), "token のない POST /api/v1/orders").toBe(403);
  const stored = sql("select count(*) from ordering.t_order where customer_order_code = :'code'", { code: csrfCode });
  expect(stored).toBe("0");
  record("proof", { orderId, code, status, csrfStatus: rejected.status(), validationPosts });
}
