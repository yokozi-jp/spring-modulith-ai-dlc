import type { Page } from "@playwright/test";

import { chooseSeededTenant, logIn, sessionCookieName, webOrigin } from "./environment";
import { expect, test } from "./fixtures";
import type { OrderListPage } from "./order-list-page";

test.use({ storageState: { cookies: [], origins: [] } });

// 一覧が成功した後に、アプリのセッションを失った状態で絞り込みを操作し、401 を受ける。
// アプリのセッションは Cookie で引くため、Cookie を外した要求は、DB のセッションを消した要求と同じく 401 になる。
async function expireSessionThenFilter(page: Page, orderListPage: OrderListPage): Promise<void> {
  await logIn(page);
  await orderListPage.goto();
  await expect(orderListPage.heading).toBeVisible();

  await page.context().clearCookies({ name: sessionCookieName });
  await orderListPage.statusLink("確定済み").click();
}

test("API が成功した後に操作が 401 になると、ログインへ移る前に /session-expired で伝える", async ({
  page,
  orderListPage,
}) => {
  await expireSessionThenFilter(page, orderListPage);

  await expect(page).toHaveURL(`${webOrigin}/session-expired`);
  await expect(
    page.getByRole("heading", { level: 1, name: "ログインの有効期限が切れました" }),
  ).toBeVisible();
  await expect(page.getByText("直前の操作は完了していない可能性があります。")).toBeVisible();
});

// Keycloak の SSO セッションは残るので、「もう一度ログイン」の後は入力画面を挟まずに /tenants へ戻る。
test("/session-expired の「もう一度ログイン」から、操作していた画面へ戻る", async ({
  page,
  orderListPage,
}) => {
  await expireSessionThenFilter(page, orderListPage);
  await expect(page).toHaveURL(`${webOrigin}/session-expired`);

  await page.getByRole("link", { name: "もう一度ログイン" }).click();
  await expect(page).toHaveURL(`${webOrigin}/tenants`);
  await chooseSeededTenant(page);

  await expect(page).toHaveURL(`${webOrigin}/orders?status=CONFIRMED`);
  await expect(orderListPage.heading).toBeVisible();
});
