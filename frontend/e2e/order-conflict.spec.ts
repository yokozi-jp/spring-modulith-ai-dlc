import type { Page } from "@playwright/test";

import { expect, test } from "./fixtures";
import { createOrder, onSaleProduct, readJson, uniqueCode } from "./orders";

// 同じ注文を 2 つのタブで開き、楽観的ロックの競合と、画面が示す回復の選択肢ごとの結果を確かめる。
// タブ A は作成の直後の版（lockNo 1）を表示したまま操作し、タブ B が先に明細を保存する。

const linesConflict = "ほかの操作で注文が更新されました。上に最新の内容を表示しています。";
const linesChanged = "明細を変更しました。";

function editor(page: Page) {
  return page.getByRole("form", { name: "明細の変更" });
}

// 詳細の明細の表の、1 行目の数量。
function firstLineQuantity(page: Page) {
  return page.getByRole("table").getByRole("row").nth(1).getByRole("cell").nth(2);
}

async function saveQuantity(page: Page, quantity: number): Promise<void> {
  await editor(page).getByLabel("数量").fill(String(quantity));
  await editor(page).getByRole("button", { name: "明細を保存する" }).click();
}

async function openTwoTabs(page: Page) {
  const product = await onSaleProduct(page.request);
  const orderId = await createOrder(page, {
    code: uniqueCode("E2E-LOCK"),
    productName: product.productName,
    quantity: 1,
  });
  const other = await page.context().newPage();
  await other.goto(`/orders/${orderId}`);
  // タブ B が先に保存し、注文の版を進める。
  await saveQuantity(other, 2);
  await expect(other.getByText(linesChanged)).toBeVisible();
  return { orderId, other };
}

async function conflictOnLines(page: Page): Promise<void> {
  await saveQuantity(page, 3);
  await expect(page.getByText(linesConflict)).toBeVisible();
  await expect(page.getByRole("button", { name: "変更を捨てて最新を表示する" })).toBeVisible();
  await expect(page.getByRole("button", { name: "最新の版に変更を適用し直す" })).toBeVisible();
}

test("明細の競合で「変更を捨てて最新を表示する」を選ぶと、ほかのタブの明細に戻り、続けて保存できる", async ({
  page,
}, testInfo) => {
  await openTwoTabs(page);
  await conflictOnLines(page);
  await testInfo.attach("conflict-choices", {
    body: await page.screenshot(),
    contentType: "image/png",
  });

  await page.getByRole("button", { name: "変更を捨てて最新を表示する" }).click();

  await expect(page.getByRole("button", { name: "変更を捨てて最新を表示する" })).toHaveCount(0);
  await expect(editor(page).getByLabel("数量")).toHaveValue("2");
  await expect(firstLineQuantity(page)).toHaveText("2");
  await saveQuantity(page, 4);
  await expect(page.getByText(linesChanged)).toBeVisible();
  await expect(firstLineQuantity(page)).toHaveText("4");
});

test("明細の競合で「最新の版に変更を適用し直す」を選ぶと、自分の入力で保存し、ほかのタブでも見える", async ({
  page,
}) => {
  const { other } = await openTwoTabs(page);
  await conflictOnLines(page);

  await page.getByRole("button", { name: "最新の版に変更を適用し直す" }).click();

  await expect(page.getByText(linesChanged)).toBeVisible();
  await expect(firstLineQuantity(page)).toHaveText("3");
  await other.reload();
  await expect(firstLineQuantity(other)).toHaveText("3");
});

test("確定の競合は、最新の内容を表示して下書きのまま残す", async ({ page }) => {
  const { orderId } = await openTwoTabs(page);

  await page.getByRole("button", { name: "確定する" }).click();

  // 確定と取消の競合では、画面は最新の表示だけを示し、選択肢を出さない。
  await expect(
    page.getByText("ほかの操作で注文が更新されました。最新の内容を表示しています。"),
  ).toBeVisible();
  await expect(firstLineQuantity(page)).toHaveText("2");
  await expect(page.getByRole("button", { name: "変更を捨てて最新を表示する" })).toHaveCount(0);
  const order = await readJson<{ status: string }>(
    await page.request.get(`/api/orders/${orderId}`),
  );
  expect(order.status, `orderId=${orderId} の状態`).toBe("DRAFT");
});
