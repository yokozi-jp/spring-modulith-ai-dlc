// 決済代行の拒否と一時障害（5xx、タイムアウト）の表示と、代表の注文の決済の欄を確かめる。
// features/order-payment.md の `pay-declined`、`pay-transient`、`pay-seeded`。
// params: cases=declined,status,delay,seeded（既定はすべて）
async function createAndConfirm({ page, expect, gateway, orders }, failure) {
  const product = await orders.onSaleProduct(page.request);
  const code = orders.uniqueCode("VERIFY");
  const orderId = await orders.createOrder(page, { code, productName: product.productName, quantity: 1 });
  // 確定は、一覧の「<客先注文番号> の詳細」から開き直した詳細の画面で行う（一覧からの入口）。
  await page.goto("/orders?status=DRAFT");
  await page.getByRole("link", { name: `${code} の詳細` }).click();
  await expect(page.getByRole("heading", { level: 1, name: `注文 ${code}` })).toBeVisible();
  const restore = await gateway.failChargesFor(orderId, failure);
  await page.getByRole("button", { name: "確定する" }).click();
  await expect(page.getByText("注文を確定しました。")).toBeVisible();
  return { orderId, restore };
}

async function declined(tools) {
  const { orderId, restore } = await createAndConfirm(tools, { kind: "declined" });
  try {
    await tools.orders.expectPaymentText(tools.page, "決済代行が決済を拒否しました。");
    await tools.step("declined");
    tools.record("declined", { orderId, charges: await tools.gateway.chargeCount(orderId) });
  } finally {
    await restore();
  }
}

async function transient(tools, failure) {
  const { page, expect, gateway, orders, sql, step, record } = tools;
  const { orderId, restore } = await createAndConfirm(tools, failure);
  try {
    await expect.poll(() => gateway.chargeCount(orderId)).toBeGreaterThanOrEqual(1);
    await expect(page.getByRole("region", { name: "決済" }).getByRole("status")).toHaveText(
      "決済の結果を確かめています。",
    );
    await step(`${failure.kind}-processing`);
    await orders.expectPaymentText(page, orders.paymentNotYetText);
    await step(`${failure.kind}-not-yet`);
    expect(await orders.listPayments(page.request, orderId)).toEqual([]);
    expect(sql("select count(*) from payment.t_payment where order_public_id = :'orderId'", { orderId })).toBe("0");
  } finally {
    await restore();
  }
  const reload = page.getByRole("button", { name: "決済の状態を読み直す" });
  await reload.click();
  await expect(page.getByRole("region", { name: "決済" }).getByRole("status")).toHaveText(
    "決済の結果を確かめています。",
  );
  await expect(reload).toHaveCount(0);
  await step(`${failure.kind}-reloading`);
  await orders.expectPaymentText(page, orders.paymentNotYetText);
  const charges = await gateway.chargeCount(orderId);
  record(failure.kind, { orderId, charges });
  expect(charges, "成功に戻した後も請求は 1 回").toBe(1);
}

async function seeded({ page, expect, orders, step }) {
  const expected = { "SEED-C03": "決済済み", "SEED-C05": "決済代行が決済を拒否しました。", "SEED-C06": orders.paymentNotYetText };
  for (const [code, text] of Object.entries(expected)) {
    await page.goto("/orders?status=CONFIRMED");
    await page.getByRole("link", { name: `${code} の詳細` }).click();
    await expect(page.getByRole("heading", { level: 1, name: `注文 ${code}` })).toBeVisible();
    await orders.expectPaymentText(page, text);
    await step(`seeded-${code}`);
  }
}

export default async function (tools) {
  const cases = (tools.params.cases ?? "declined,status,delay,seeded").split(",");
  if (cases.includes("declined")) await declined(tools);
  if (cases.includes("status")) await transient(tools, { kind: "status", status: 503 });
  if (cases.includes("delay")) await transient(tools, { kind: "delay", milliseconds: 3000 });
  if (cases.includes("seeded")) await seeded(tools);
}
