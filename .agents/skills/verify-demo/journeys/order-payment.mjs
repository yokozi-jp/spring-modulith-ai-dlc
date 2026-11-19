// 注文を画面で作成して確定し、Listener の請求と決済の表示、決済記録の行、決済代行への請求の回数を確かめる。
// features/order-payment.md の `pay-success`。params: quantity=<1 以上>（既定 2）
export default async function ({ page, expect, step, gateway, sql, params, record }) {
  const quantity = Number(params.quantity ?? 2);
  const products = (await (await page.request.get("/api/products")).json()).items;
  const product = products.find((item) => item.salesStatus === "ON_SALE");
  if (product === undefined) throw new Error("販売中の商品がありません。stack.sh up のシーダーを確かめてください。");
  const code = `VERIFY-${crypto.randomUUID().slice(0, 18)}`;

  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "注文" })).toBeVisible();
  await page.getByRole("link", { name: "注文を作成する" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "注文の作成" })).toBeVisible();
  await page.getByLabel("客先注文番号").fill(code);
  await page.getByLabel("商品").selectOption({ label: product.productName });
  await page.getByLabel("数量").fill(String(quantity));
  await step("filled");
  await page.getByRole("button", { name: "作成する" }).click();
  await page.waitForURL(/\/orders\/[0-9a-f-]{36}$/u);
  const orderId = new URL(page.url()).pathname.slice("/orders/".length);
  await expect(page.getByRole("heading", { level: 1, name: `注文 ${code}` })).toBeVisible();
  await step("draft");

  await page.getByRole("button", { name: "確定する" }).click();
  await expect(page.getByText("注文を確定しました。")).toBeVisible();
  await step("confirmed");

  const payment = page.getByRole("region", { name: "決済" });
  await expect(payment.getByRole("status")).toHaveText("決済済み", { timeout: 30_000 });
  const total = product.unitPrice * quantity;
  const yen = new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY" }).format(total);
  await expect(payment).toContainText(yen);
  await step("paid");

  // 副作用: 決済記録の行、注文の状態、決済代行が受けた請求の回数。
  const row = sql(`select p.payment_status_typ || ',' || p.charged_amount_jpy::bigint || ',' || o.order_status_typ from payment.t_payment p join ordering.t_order o on o.public_id = p.order_public_id where p.order_public_id = '${orderId}'`);
  const charges = await gateway.chargeCount(orderId);
  record("proof", { orderId, code, total, row, charges });
  expect(charges, "決済代行への請求の回数").toBe(1);
  expect(row, "決済記録の状態、金額、注文の状態").toBe(`PAID,${total},CONFIRMED`);

  await page.getByRole("link", { name: "注文の一覧へ戻る" }).click();
  await expect(page.getByRole("link", { name: `${code} の詳細` })).toBeVisible();
  await step("listed");
}
