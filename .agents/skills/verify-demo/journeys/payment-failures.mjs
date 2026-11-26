// 決済代行の拒否と一時障害（5xx、タイムアウト）の表示と、代表の注文の決済の欄を確かめる。
// features/order-payment.md の `pay-declined`、`pay-transient`、`pay-seeded`。
// params: cases=declined,status,delay,seeded（既定はすべて）
async function createAndConfirm({ page, expect, gateway, helpers, screensOf }, failure) {
  const product = await helpers.onSaleProduct(page.request);
  const code = helpers.uniqueCode("VERIFY");
  const { create, detail, list } = screensOf(page);
  const orderId = await create.create({ code, productName: product.productName, quantity: 1 });
  // 確定は、一覧の「<客先注文番号> の詳細」から開き直した詳細の画面で行う（一覧からの入口）。
  await list.openDetail(code, "DRAFT");
  await expect(detail.heading(code)).toBeVisible();
  const restore = await gateway.failChargesFor(orderId, failure);
  await detail.confirmButton.click();
  await expect(detail.confirmedNotice).toBeVisible();
  return { orderId, restore };
}

async function declined(tools) {
  const { orderId, restore } = await createAndConfirm(tools, { kind: "declined" });
  try {
    await tools.expectPaymentText(tools.page, "決済代行が決済を拒否しました。");
    await tools.step("declined");
    tools.record("declined", { orderId, charges: await tools.gateway.chargeCount(orderId) });
  } finally {
    await restore();
  }
}

async function transient(tools, failure) {
  const { page, expect, expectPaymentText, gateway, helpers, screensOf, sql, step, record } = tools;
  const detail = screensOf(page).detail;
  const { orderId, restore } = await createAndConfirm(tools, failure);
  try {
    await expect.poll(() => gateway.chargeCount(orderId)).toBeGreaterThanOrEqual(1);
    await expect(detail.paymentStatus).toHaveText("決済の結果を確かめています。");
    await step(`${failure.kind}-processing`);
    await expectPaymentText(page, detail.paymentNotYetText);
    await step(`${failure.kind}-not-yet`);
    expect(await helpers.listPayments(page.request, orderId)).toEqual([]);
    expect(sql("select count(*) from payment.t_payment where order_public_id = :'orderId'", { orderId })).toBe("0");
  } finally {
    await restore();
  }
  await detail.rereadPaymentButton.click();
  await expect(detail.paymentStatus).toHaveText("決済の結果を確かめています。");
  await expect(detail.rereadPaymentButton).toHaveCount(0);
  await step(`${failure.kind}-reloading`);
  await expectPaymentText(page, detail.paymentNotYetText);
  const charges = await gateway.chargeCount(orderId);
  record(failure.kind, { orderId, charges });
  expect(charges, "成功に戻した後も請求は 1 回").toBe(1);
}

async function seeded(tools) {
  const { page, expect, expectPaymentText, screensOf, step } = tools;
  const { list, detail } = screensOf(page);
  const expected = {
    "SEED-C03": "決済済み",
    "SEED-C05": "決済代行が決済を拒否しました。",
    "SEED-C06": detail.paymentNotYetText,
  };
  for (const [code, text] of Object.entries(expected)) {
    await list.openDetail(code, "CONFIRMED");
    await expect(detail.heading(code)).toBeVisible();
    await expectPaymentText(page, text);
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
