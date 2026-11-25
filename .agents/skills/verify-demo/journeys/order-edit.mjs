// 下書きの明細の保存、2 つのタブの競合と 2 つの回復、取り消しと取り消した後の画面を確かめる。
// features/order-edit.md の `edit-save`、`edit-conflict-discard`、`edit-conflict-reapply`、`edit-cancel`、`edit-locked`。
async function draft({ page, orders, screensOf }) {
  const product = await orders.onSaleProduct(page.request);
  return screensOf(page).create.create({ code: orders.uniqueCode("VERIFY"), productName: product.productName, quantity: 1 });
}

async function openConflict(tools) {
  const { page, context, expect, step, screensOf } = tools;
  const detail = screensOf(page).detail;
  const orderId = await draft(tools);
  const other = await context.newPage();
  const otherDetail = screensOf(other).detail;
  await otherDetail.goto(orderId);
  await otherDetail.saveQuantity(2);
  await expect(otherDetail.linesChangedNotice).toBeVisible();
  await step("other-saved", other);
  await detail.saveQuantity(3);
  await expect(detail.linesConflictNotice).toBeVisible();
  await expect(detail.discardChangesButton).toBeVisible();
  await expect(detail.reapplyChangesButton).toBeVisible();
  return { orderId, other };
}

export default async function (tools) {
  const { page, expect, step, sql, record, screensOf } = tools;
  const detail = screensOf(page).detail;
  const quantityOf = (orderId) =>
    sql(
      "select l.ordered_count from ordering.t_order_line l join ordering.t_order o using (order_id) where o.public_id = :'orderId'",
      { orderId },
    );

  // edit-save
  const saved = await draft(tools);
  await detail.saveQuantity(3);
  await expect(detail.linesChangedNotice).toBeVisible();
  await expect(detail.firstLineQuantity).toHaveText("3");
  expect(quantityOf(saved)).toBe("3");
  await step("saved");

  // edit-conflict-discard
  const discard = await openConflict(tools);
  await step("conflict");
  await detail.discardChangesButton.click();
  await expect(detail.quantityInput).toHaveValue("2");
  await expect(detail.firstLineQuantity).toHaveText("2");
  await detail.saveQuantity(4);
  await expect(detail.linesChangedNotice).toBeVisible();
  await expect(detail.firstLineQuantity).toHaveText("4");
  expect(quantityOf(discard.orderId)).toBe("4");
  await step("discarded");
  await discard.other.close();

  // edit-conflict-reapply
  const reapply = await openConflict(tools);
  await detail.reapplyChangesButton.click();
  await expect(detail.linesChangedNotice).toBeVisible();
  await expect(detail.firstLineQuantity).toHaveText("3");
  await reapply.other.reload();
  await expect(screensOf(reapply.other).detail.firstLineQuantity).toHaveText("3");
  await step("reapplied");
  await step("reapplied-other", reapply.other);
  expect(quantityOf(reapply.orderId)).toBe("3");
  await reapply.other.close();

  // edit-cancel と edit-locked
  const cancelled = await draft(tools);
  await detail.cancelButton.click();
  await expect(detail.cancelledNotice).toBeVisible();
  await expect(page.getByText("取消済み", { exact: true })).toBeVisible();
  await expect(detail.linesEditor).toHaveCount(0);
  await expect(detail.confirmButton).toHaveCount(0);
  await expect(detail.cancelButton).toHaveCount(0);
  await expect(detail.paymentStatus).toHaveText("この注文は決済の対象ではありません。");
  await step("cancelled");
  const status = sql("select order_status_typ from ordering.t_order where public_id = :'orderId'", { orderId: cancelled });
  expect(status).toBe("CANCELLED");

  // 確定した注文も、同じく変更の入口が消える。
  const confirmed = await draft(tools);
  await detail.confirmButton.click();
  await expect(detail.confirmedNotice).toBeVisible();
  await expect(detail.linesEditor).toHaveCount(0);
  await expect(detail.confirmButton).toHaveCount(0);
  await expect(detail.cancelButton).toHaveCount(0);
  await step("confirmed-locked");
  record("orders", { saved, discard: discard.orderId, reapply: reapply.orderId, cancelled, confirmed });
}
