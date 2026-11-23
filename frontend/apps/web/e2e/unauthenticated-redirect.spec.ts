import { logIn, signInAndChooseTenant, webOrigin } from "./environment";
import { expect, test } from "./fixtures";

// ログアウトで共有の storageState のセッションを終わらせないよう、専用の context でログインする。
test.use({ storageState: { cookies: [], origins: [] } });

test("ログアウト後に / を開くと資格情報の入力を求められ、ログインすると / へ戻る", async ({
  page,
}) => {
  await logIn(page);
  await page.getByRole("button", { name: "ログアウト" }).click();
  await expect(page).toHaveURL(`${webOrigin}/logged-out`);

  // load を待つと、SPA が 401 を受けて始めるログインへの遷移と競合する。
  await page.goto("/", { waitUntil: "commit" });
  await expect(page.getByLabel("Username or email")).toBeVisible();
  // ログインの後の着地が /tenants であることが、/api/** の要求を保存しない（NullRequestCache）証拠になる。
  await signInAndChooseTenant(page);

  await expect(
    page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" }),
  ).toBeVisible();
});
