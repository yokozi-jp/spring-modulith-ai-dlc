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

  await page.goto("/");
  await expect(page.getByLabel("Username or email")).toBeVisible();
  // 戻り先は画面の URL になり、401 を返した API の URL（/api/**）にならない。
  await signInAndChooseTenant(page);

  await expect(page).toHaveURL(`${webOrigin}/`);
  await expect(
    page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" }),
  ).toBeVisible();
});
