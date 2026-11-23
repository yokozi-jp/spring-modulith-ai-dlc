import { signInOnKeycloak, webOrigin } from "./environment";
import { expect, test } from "./fixtures";
import { openExpectingLogin } from "./pages";

test.use({ storageState: { cookies: [], origins: [] } });

// 401 を受ける経路が違う。/tenants は loader、/orders は _tenant の beforeLoad が受ける。
test("未認証で /tenants を開くと、Keycloak の入力画面へ移る", async ({ page }) => {
  await openExpectingLogin(page, "/tenants");
});

test("未認証で /orders を開くと、Keycloak の入力画面へ移る", async ({ page }) => {
  await openExpectingLogin(page, "/orders");
});

// Keycloak の SSO セッションは残るため、遷移の後は入力画面を挟まずに /tenants へ戻る。
test("テナントを選ぶ操作がアプリのセッション切れで 401 になると、ログインし直して /tenants へ戻る", async ({
  page,
  context,
}) => {
  await page.goto("/oauth2/authorization/web");
  await signInOnKeycloak(page);
  const heading = page.getByRole("heading", { level: 1, name: "テナントを選んでください" });
  await expect(heading).toBeVisible();

  await context.clearCookies({ name: "__Host-APP_SESSION" });
  // 401 を受けた元の文書も一覧を取り直して 401 になるため、200 は読み込み直した文書だけが返す。
  const reloaded = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/memberships" && response.status() === 200,
  );
  await page.getByRole("button", { name: "サンプル商事" }).click();
  await reloaded;

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
