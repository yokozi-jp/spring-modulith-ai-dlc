import { webOrigin } from "./environment";
import { expect, test } from "./fixtures";

test.use({ storageState: { cookies: [], origins: [] } });

// 認可リクエストを持つセッションがないので、バックエンドは認証に失敗し、配信点の proxy を通って /login-failed へ移す。
test("認可リクエストのないログインのコールバックを開くと、/login-failed の画面に着く", async ({
  page,
}) => {
  const response = await page.goto("/login/oauth2/code/web?code=x&state=y");

  const callback = await response?.request().redirectedFrom()?.response();
  expect(callback?.url()).toBe(`${webOrigin}/login/oauth2/code/web?code=x&state=y`);
  // vite preview は X-Forwarded-* を付けないので、ForwardedHeaderFilter の 303 にならない。
  expect(callback?.status()).toBe(302);
  await expect(page).toHaveURL(`${webOrigin}/login-failed`);
  await expect(
    page.getByRole("heading", { level: 1, name: "ログインできませんでした" }),
  ).toBeVisible();
  // Spring Security の既定のログイン画面へ落ちていないことを、その画面の見出しで確かめる。
  await expect(page.getByText("Please sign in")).toHaveCount(0);
});
