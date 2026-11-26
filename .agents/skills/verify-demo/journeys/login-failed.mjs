// ログインのコールバックの失敗で /login-failed の画面に着くことを確かめる。
// features/login-logout.md の `auth-login-failed`。
export const login = false;

export default async function ({ page, expect, step, record, webOrigin, idpOrigin, helpers }) {
  await page.goto("/oauth2/authorization/web");
  await expect(page).toHaveURL((url) => url.origin === idpOrigin);
  const state = new URL(page.url()).searchParams.get("state");
  expect(state, "Keycloak への認可リクエストの state").not.toBeNull();
  await step("keycloak");

  const marker = helpers.uniqueCode("VERIFY");
  record("errorDescriptionMarker", marker);
  const denied = new URLSearchParams({ error: "access_denied", error_description: marker, state });
  await page.goto(`${webOrigin}/login/oauth2/code/web?${denied}`);
  await expect(page).toHaveURL(`${webOrigin}/login-failed`);
  await expect(page.getByRole("heading", { level: 1, name: "ログインできませんでした" })).toBeVisible();
  await expect(page.getByText("もう一度お試しください。")).toBeVisible();
  await expect(page.getByText("Please sign in")).toHaveCount(0);
  await step("login-failed-idp-denied");

  await page.goto(`${webOrigin}/login/oauth2/code/web?code=x&state=y`);
  await expect(page).toHaveURL(`${webOrigin}/login-failed`);
  await expect(page.getByRole("heading", { level: 1, name: "ログインできませんでした" })).toBeVisible();
  await step("login-failed-no-authorization-request");

  await page.getByRole("link", { name: "もう一度ログイン" }).click();
  await expect(page).toHaveURL((url) => url.origin === idpOrigin);
  await expect(page.getByLabel("Username or email")).toBeVisible();
  await step("login-again");
}
