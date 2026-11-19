// 作成の画面の入力の誤り、明細の追加と削除、販売中の商品だけの選択肢、重複、CSRF、下書きの作成を確かめる。
// features/order-create.md の `create-validation`、`create-lines`、`create-on-sale-only`、`create-duplicate`、`create-csrf`、`create-draft`。
export default async function ({ page, expect, step, sql, orders, record }) {
  const products = await orders.listProducts(page.request);
  const onSale = products.filter((item) => item.salesStatus === "ON_SALE");
  const discontinued = products.find((item) => item.salesStatus === "DISCONTINUED");
  if (onSale.length < 2 || discontinued === undefined) {
    throw new Error("販売中の商品 2 つと販売終了の商品 1 つが要ります。stack.sh up のシーダーを確かめてください。");
  }

  await page.goto("/orders");
  await page.getByRole("link", { name: "注文を作成する" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "注文の作成" })).toBeVisible();

  // create-on-sale-only
  const select = page.getByLabel("商品");
  await expect(select.getByRole("option", { name: discontinued.productName })).toHaveCount(0);
  await expect(select.getByRole("option", { name: onSale[0].productName })).toHaveCount(1);

  // create-validation
  const posts = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && new URL(request.url()).pathname === "/api/orders") posts.push(request.url());
  });
  await page.getByRole("button", { name: "作成する" }).click();
  await expect(page.getByText("客先注文番号を入力してください。")).toBeVisible();
  await expect(page.getByText("商品を選んでください。", { exact: true })).toBeVisible();
  // 誤りの残るフォームは送信の検証を走らせ直さないので、範囲外の数量は開き直した画面で確かめる（features の Gotchas）。
  await page.goto("/orders/new");
  await page.getByLabel("客先注文番号").fill(orders.uniqueCode("VERIFY"));
  await select.selectOption({ label: onSale[0].productName });
  await page.getByLabel("数量").fill("0");
  await page.getByRole("button", { name: "作成する" }).click();
  await expect(page.getByText(/^数量は .* 以上 .* 以下の整数にしてください。$/u)).toBeVisible();
  await expect(page).toHaveURL(/\/orders\/new$/u);
  const validationPosts = [...posts];
  expect(validationPosts, "入力の誤りでは POST /api/orders を送らない").toEqual([]);
  await step("validation");

  // create-duplicate
  await page.getByLabel("客先注文番号").fill("SEED-C01");
  await select.selectOption({ label: onSale[0].productName });
  await page.getByLabel("数量").fill("1");
  await page.getByRole("button", { name: "作成する" }).click();
  await expect(page.getByText(/この客先注文番号は既に使われている可能性があります。/u)).toBeVisible();
  await expect(page).toHaveURL(/\/orders\/new$/u);
  expect(sql("select count(*) from ordering.t_order where customer_order_code = 'SEED-C01'")).toBe("1");
  await step("duplicate");

  // create-lines と create-draft
  const code = orders.uniqueCode("VERIFY");
  await page.goto("/orders/new");
  await page.getByLabel("客先注文番号").fill(code);
  await page.getByRole("group", { name: "明細 1" }).getByLabel("商品").selectOption({ label: onSale[0].productName });
  await page.getByRole("group", { name: "明細 1" }).getByLabel("数量").fill("1");
  await page.getByRole("button", { name: "明細を追加する" }).click();
  await expect(page.getByRole("group", { name: "明細 2" })).toBeVisible();
  await page.getByRole("button", { name: "明細 2 を削除する" }).click();
  await expect(page.getByRole("group", { name: "明細 2" })).toHaveCount(0);
  await page.getByRole("button", { name: "明細を追加する" }).click();
  await page.getByRole("group", { name: "明細 2" }).getByLabel("商品").selectOption({ label: onSale[1].productName });
  await page.getByRole("group", { name: "明細 2" }).getByLabel("数量").fill("2");
  await step("two-lines");
  await page.getByRole("button", { name: "作成する" }).click();
  await page.waitForURL(/\/orders\/[0-9a-f-]{36}$/u);
  const orderId = new URL(page.url()).pathname.slice("/orders/".length);
  await expect(page.getByRole("heading", { level: 1, name: `注文 ${code}` })).toBeVisible();
  await expect(page.getByRole("table").getByRole("row")).toHaveCount(3);
  await step("draft");
  const status = sql("select order_status_typ from ordering.t_order where public_id = :'orderId'", { orderId });
  expect(status).toBe("DRAFT");

  // create-csrf
  const csrfCode = orders.uniqueCode("VERIFY");
  const rejected = await page.request.post("/api/orders", {
    data: { customerOrderCode: csrfCode, lines: [{ productId: onSale[0].productId, quantity: 1 }] },
  });
  expect(rejected.status(), "token のない POST /api/orders").toBe(403);
  const stored = sql("select count(*) from ordering.t_order where customer_order_code = :'code'", { code: csrfCode });
  expect(stored).toBe("0");
  record("proof", { orderId, code, status, csrfStatus: rejected.status(), validationPosts });
}
