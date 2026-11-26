import { sessionCookieName, signInOnKeycloak, webOrigin } from "./environment";
import { expect, test } from "./fixtures";
import { openExpectingLogin, tenantsHeading } from "./pages";

test.use({ storageState: { cookies: [], origins: [] } });

// 401 を受ける経路が違う。/tenants は loader、/orders は _tenant の beforeLoad が受ける。
test("未認証で /tenants を開くと、Keycloak の入力画面へ移る", async ({ page }) => {
  await openExpectingLogin(page, "/tenants");
});

test("未認証で /orders を開くと、Keycloak の入力画面へ移る", async ({ page }) => {
  await openExpectingLogin(page, "/orders");
});

// 一覧の取得が成功した後の 401 なので、ログインへ移る前に /session-expired を挟む。
// Keycloak の SSO セッションは残るため、「もう一度ログイン」の後は入力画面を挟まずに /tenants へ戻る。
test("テナントを選ぶ操作がアプリのセッション切れで 401 になると、/session-expired を挟み、ログインし直して /tenants へ戻る", async ({
  page,
  context,
}) => {
  await page.goto("/oauth2/authorization/web");
  await signInOnKeycloak(page);
  const heading = tenantsHeading(page);
  await expect(heading).toBeVisible();

  await context.clearCookies({ name: sessionCookieName });
  await page.getByRole("button", { name: "サンプル商事" }).click();
  await expect(page).toHaveURL(`${webOrigin}/session-expired`);

  await page.getByRole("link", { name: "もう一度ログイン" }).click();

  await expect(page).toHaveURL(`${webOrigin}/tenants`);
  await expect(heading).toBeVisible();
});

// test-user は 2 つのテナントに属するため、ログインの後にテナントを選ぶ画面を挟み、選んだ後の `/` が戻り先を読む。
test("未認証で /orders/new?from=e2e を開くと、ログインとテナントの選択の後に元の画面へ着く", async ({
  page,
}) => {
  await openExpectingLogin(page, "/orders/new?from=e2e");
  await signInOnKeycloak(page);
  await expect(page).toHaveURL(`${webOrigin}/tenants`);

  await page.getByRole("button", { name: "サンプル商事" }).click();

  await expect(page).toHaveURL(`${webOrigin}/orders/new?from=e2e`);
});
