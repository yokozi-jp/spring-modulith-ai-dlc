// 下書きの明細の保存、2 つのタブの競合と 2 つの回復、取り消しと取り消した後の画面を確かめる。
// features/order-edit.md の `edit-save`、`edit-conflict-discard`、`edit-conflict-reapply`、`edit-cancel`、`edit-locked`。
const conflict = "ほかの操作で注文が更新されました。上に最新の内容を表示しています。";
const changed = "明細を変更しました。";

const editor = (page) => page.getByRole("form", { name: "明細の変更" });
const firstQuantity = (page) => page.getByRole("table").getByRole("row").nth(1).getByRole("cell").nth(2);

async function save(page, quantity) {
  await editor(page).getByLabel("数量").fill(String(quantity));
  await editor(page).getByRole("button", { name: "明細を保存する" }).click();
}

async function draft({ page, orders }) {
  const product = await orders.onSaleProduct(page.request);
  return orders.createOrder(page, { code: orders.uniqueCode("VERIFY"), productName: product.productName, quantity: 1 });
}

async function openConflict(tools) {
  const { page, context, expect, step } = tools;
  const orderId = await draft(tools);
  const other = await context.newPage();
  await other.goto(`/orders/${orderId}`);
  await save(other, 2);
  await expect(other.getByText(changed)).toBeVisible();
  await step("other-saved", other);
  await save(page, 3);
  await expect(page.getByText(conflict)).toBeVisible();
  await expect(page.getByRole("button", { name: "変更を捨てて最新を表示する" })).toBeVisible();
  await expect(page.getByRole("button", { name: "最新の版に変更を適用し直す" })).toBeVisible();
  return { orderId, other };
}

export default async function (tools) {
  const { page, expect, step, sql, record } = tools;
  const quantityOf = (orderId) =>
    sql(
      "select l.ordered_count from ordering.t_order_line l join ordering.t_order o using (order_id) where o.public_id = :'orderId'",
      { vars: { orderId } },
    );

  // edit-save
  const saved = await draft(tools);
  await save(page, 3);
  await expect(page.getByText(changed)).toBeVisible();
  await expect(firstQuantity(page)).toHaveText("3");
  expect(quantityOf(saved)).toBe("3");
  await step("saved");

  // edit-conflict-discard
  const discard = await openConflict(tools);
  await step("conflict");
  await page.getByRole("button", { name: "変更を捨てて最新を表示する" }).click();
  await expect(editor(page).getByLabel("数量")).toHaveValue("2");
  await expect(firstQuantity(page)).toHaveText("2");
  await save(page, 4);
  await expect(page.getByText(changed)).toBeVisible();
  await expect(firstQuantity(page)).toHaveText("4");
  expect(quantityOf(discard.orderId)).toBe("4");
  await step("discarded");
  await discard.other.close();

  // edit-conflict-reapply
  const reapply = await openConflict(tools);
  await page.getByRole("button", { name: "最新の版に変更を適用し直す" }).click();
  await expect(page.getByText(changed)).toBeVisible();
  await expect(firstQuantity(page)).toHaveText("3");
  await reapply.other.reload();
  await expect(firstQuantity(reapply.other)).toHaveText("3");
  await step("reapplied");
  await step("reapplied-other", reapply.other);
  expect(quantityOf(reapply.orderId)).toBe("3");
  await reapply.other.close();

  // edit-cancel と edit-locked
  const cancelled = await draft(tools);
  await page.getByRole("button", { name: "取り消す" }).click();
  await expect(page.getByText("注文を取り消しました。")).toBeVisible();
  await expect(page.getByText("取消済み", { exact: true })).toBeVisible();
  await expect(editor(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "確定する" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "取り消す" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "決済" }).getByRole("status")).toHaveText(
    "この注文は決済の対象ではありません。",
  );
  await step("cancelled");
  const status = sql("select order_status_typ from ordering.t_order where public_id = :'orderId'", { vars: { orderId: cancelled } });
  expect(status).toBe("CANCELLED");
  record("orders", { saved, discard: discard.orderId, reapply: reapply.orderId, cancelled });
}
