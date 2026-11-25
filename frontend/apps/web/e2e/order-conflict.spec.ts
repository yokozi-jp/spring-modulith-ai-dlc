import { readJson } from "./api";
import type { Product } from "./fixtures";
import { expect, test, uniqueCode } from "./fixtures";
import type { OrderCreatePage } from "./order-create-page";
import type { OrderDetailPage } from "./order-detail-page";

// 同じ注文を 2 つのタブで開き、楽観的ロックの競合と、画面が示す回復の選択肢ごとの結果を確かめる。
// タブ A は作成の直後の版（lockNo 1）を表示したまま操作し、タブ B が先に明細を保存する。

async function openTwoTabs(
  {
    orderCreatePage,
    orderDetailPage,
  }: { orderCreatePage: OrderCreatePage; orderDetailPage: OrderDetailPage },
  product: Product,
) {
  const orderId = await orderCreatePage.create({
    code: uniqueCode("E2E-LOCK"),
    productName: product.productName,
    quantity: 1,
  });
  const other = await orderDetailPage.openInNewTab(orderId);
  // タブ B が先に保存し、注文の版を進める。
  await other.saveQuantity(2);
  await expect(other.linesChangedNotice).toBeVisible();
  return { orderId, other };
}

async function conflictOnLines(detail: OrderDetailPage): Promise<void> {
  await detail.saveQuantity(3);
  await expect(detail.linesConflictNotice).toBeVisible();
  await expect(detail.discardChangesButton).toBeVisible();
  await expect(detail.reapplyChangesButton).toBeVisible();
}

test("明細の競合で「変更を捨てて最新を表示する」を選ぶと、ほかのタブの明細に戻り、続けて保存できる", async ({
  page,
  orderCreatePage,
  orderDetailPage,
  productFactory,
}, testInfo) => {
  await openTwoTabs({ orderCreatePage, orderDetailPage }, await productFactory());
  await conflictOnLines(orderDetailPage);
  await testInfo.attach("conflict-choices", {
    body: await page.screenshot(),
    contentType: "image/png",
  });

  await orderDetailPage.discardChangesButton.click();

  await expect(orderDetailPage.discardChangesButton).toHaveCount(0);
  await expect(orderDetailPage.quantityInput).toHaveValue("2");
  await expect(orderDetailPage.firstLineQuantity).toHaveText("2");
  await orderDetailPage.saveQuantity(4);
  await expect(orderDetailPage.linesChangedNotice).toBeVisible();
  await expect(orderDetailPage.firstLineQuantity).toHaveText("4");
});

test("明細の競合で「最新の版に変更を適用し直す」を選ぶと、自分の入力で保存し、ほかのタブでも見える", async ({
  orderCreatePage,
  orderDetailPage,
  productFactory,
}) => {
  const { other } = await openTwoTabs({ orderCreatePage, orderDetailPage }, await productFactory());
  await conflictOnLines(orderDetailPage);

  await orderDetailPage.reapplyChangesButton.click();

  await expect(orderDetailPage.linesChangedNotice).toBeVisible();
  await expect(orderDetailPage.firstLineQuantity).toHaveText("3");
  await other.reload();
  await expect(other.firstLineQuantity).toHaveText("3");
});

test("確定の競合は、最新の内容を表示して下書きのまま残す", async ({
  page,
  orderCreatePage,
  orderDetailPage,
  productFactory,
}) => {
  const { orderId } = await openTwoTabs(
    { orderCreatePage, orderDetailPage },
    await productFactory(),
  );

  await orderDetailPage.confirmButton.click();

  // 確定と取消の競合では、画面は最新の表示だけを示し、選択肢を出さない。
  await expect(orderDetailPage.confirmConflictNotice).toBeVisible();
  await expect(orderDetailPage.firstLineQuantity).toHaveText("2");
  await expect(orderDetailPage.discardChangesButton).toHaveCount(0);
  const order = await readJson<{ status: string }>(
    await page.request.get(`/api/orders/${orderId}`),
  );
  expect(order.status, `orderId=${orderId} の状態`).toBe("DRAFT");
});
