// 注文を画面で作成して確定し、Listener の請求と決済の表示、決済記録の行、決済代行への請求の回数を確かめる。
// features/order-payment.md の `pay-confirm` と `pay-success`。params: quantity=<1 以上>（既定 2）
export default async function ({ page, expect, step, gateway, sql, orders, screensOf, params, record }) {
  const { create, detail } = screensOf(page);
  const quantity = Number(params.quantity ?? 2);
  const product = await orders.onSaleProduct(page.request);
  const code = orders.uniqueCode("VERIFY");

  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "注文" })).toBeVisible();
  await page.getByRole("link", { name: "注文を作成する" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "注文の作成" })).toBeVisible();
  const orderId = await create.create({ code, productName: product.productName, quantity }, () => step("filled"));
  await expect(page.getByRole("heading", { level: 1, name: `注文 ${code}` })).toBeVisible();
  await step("draft");

  await detail.confirmButton.click();
  await expect(detail.confirmedNotice).toBeVisible();
  await step("confirmed");

  await expect(detail.paymentStatus).toHaveText("決済済み", { timeout: detail.paymentTextTimeoutMs });
  const total = product.unitPrice * quantity;
  const yen = new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY" }).format(total);
  await expect(detail.paymentRegion).toContainText(yen);
  await step("paid");

  const row = sql(
    "select p.payment_status_typ, p.charged_amount_jpy::bigint, o.order_status_typ " +
      "from payment.t_payment p join ordering.t_order o on o.public_id = p.order_public_id " +
      "where p.order_public_id = :'orderId'",
    { orderId },
  );
  const charges = await gateway.chargeCount(orderId);
  record("proof", { orderId, code, total, row, charges });
  expect(row, "決済記録の状態、金額、注文の状態").toBe(`PAID|${total}|CONFIRMED`);
  expect(charges, "決済代行への請求の回数").toBe(1);

  await page.getByRole("link", { name: "注文の一覧へ戻る" }).click();
  await expect(page.getByRole("link", { name: `${code} の詳細` })).toBeVisible();
  await step("listed");
}
