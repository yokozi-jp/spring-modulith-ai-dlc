import { chooseSeededTenant, logIn, sessionCookieName, webOrigin } from "./environment";
import { expect, test } from "./fixtures";

test.use({ storageState: { cookies: [], origins: [] } });

// Cookie を外して 401 を起こす方法は docs/e2e/testing-strategy.md の「認証」にある。
test("API が成功した後に操作が 401 になると /session-expired で伝え、「もう一度ログイン」から操作していた画面へ戻る", async ({
  page,
  orderListPage,
}) => {
  await logIn(page);
  await orderListPage.goto();
  await expect(orderListPage.heading).toBeVisible();

  await page.context().clearCookies({ name: sessionCookieName });
  await orderListPage.statusLink("確定済み").click();

  await expect(page).toHaveURL(`${webOrigin}/session-expired`);
  await expect(
    page.getByRole("heading", { level: 1, name: "ログインの有効期限が切れました" }),
  ).toBeVisible();

  await page.getByRole("link", { name: "もう一度ログイン" }).click();
  await chooseSeededTenant(page);

  await expect(page).toHaveURL(`${webOrigin}/orders?status=CONFIRMED`);
});
