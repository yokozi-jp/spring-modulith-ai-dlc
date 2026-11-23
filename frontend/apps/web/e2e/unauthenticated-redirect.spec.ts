import { idpOrigin, signInOnKeycloak, webOrigin } from "./environment";
import { expect, test } from "./fixtures";

// 共有のログイン済みの storageState を使わず、未認証から始める。
test.use({ storageState: { cookies: [], origins: [] } });

// 30 秒の遷移ガードは tab ごとなので、画面ごとに tab を分ける。
// load を待つと、SPA が 401 を受けて始めるログインへの遷移と競合する。
test("未認証で認証必須の画面を開くと、どの画面でも Keycloak の入力画面へ移る", async ({
  context,
}) => {
  await Promise.all(
    ["/tenants", "/orders", "/members"].map(async (path) => {
      const page = await context.newPage();
      await page.goto(path, { waitUntil: "commit" });

      await expect(page, path).toHaveURL((url) => url.origin === idpOrigin);
      await expect(page.getByLabel("Username or email"), path).toBeVisible();
    }),
  );
});

// Keycloak の SSO セッションは残るため、遷移の後は入力画面を挟まずに /tenants へ戻る。
test("アプリのセッションを失った後の更新が 401 になると、ログインし直して /tenants へ戻る", async ({
  page,
  context,
}) => {
  await page.goto("/oauth2/authorization/web");
  await signInOnKeycloak(page);
  const heading = page.getByRole("heading", { level: 1, name: "テナントを選んでください" });
  await expect(heading).toBeVisible();

  await context.clearCookies({ name: "APP_SESSION" });
  const callback = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/login/oauth2/code/web",
  );
  await page.getByRole("button", { name: "サンプル商事" }).click();
  await callback;

  await expect(page).toHaveURL(`${webOrigin}/tenants`);
  await expect(heading).toBeVisible();
});
