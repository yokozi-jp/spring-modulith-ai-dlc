// Keycloak の画面からのログイン、セッションの入れ替わり、ログアウト、もう一度ログインを確かめる。
// features/login-logout.md の `auth-login`、`auth-session`、`auth-logout`、`auth-login-again`。
export const login = false;

async function sessionCookie(context) {
  return (await context.cookies()).find(({ name }) => name === "APP_SESSION")?.value;
}

export default async function ({ page, context, expect, step, login: signIn, request, record }) {
  await page.goto("/oauth2/authorization/web");
  await expect(page).toHaveURL(/^http:\/\/127\.0\.0\.1:8081\//u);
  await step("keycloak");
  const before = await sessionCookie(context);

  await signIn();
  await expect(page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" })).toBeVisible();
  const session = await sessionCookie(context);
  expect(session, "ログインの後の APP_SESSION").toBeDefined();
  expect(session, "ログインで APP_SESSION が入れ替わる").not.toBe(before);
  expect((await page.request.get("/api/missing")).status(), "認証済みの /api/missing").toBe(404);
  await step("home");

  await page.getByRole("button", { name: "ログアウト" }).click();
  await expect(page.getByRole("heading", { name: "ログアウトしました" })).toBeVisible();
  await step("logged-out");
  const fresh = await request.newContext({ baseURL: "http://localhost:5173" });
  try {
    const replayed = await fresh.get("/api/missing", { headers: { Cookie: `APP_SESSION=${session}` } });
    record("replayedStatus", replayed.status());
    expect(replayed.status(), "ログアウトの前の APP_SESSION").toBe(401);
  } finally {
    await fresh.dispose();
  }

  await page.getByRole("link", { name: "もう一度ログイン" }).click();
  await expect(page).toHaveURL(/^http:\/\/127\.0\.0\.1:8081\//u);
  await expect(page.getByLabel("Username or email")).toBeVisible();
  await step("login-again");
}
