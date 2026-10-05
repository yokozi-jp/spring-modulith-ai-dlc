import type { BrowserContext, Locator, Page } from "@playwright/test";
import { expect, test } from "@playwright/test";

import { signInOnKeycloak } from "./environment";

// 共有の storageState のセッションを終わらせないよう、各テストは専用の context でログインする。
test.use({ storageState: { cookies: [], origins: [] } });

// compose-test が Keycloak の issuer を固定している（auth.setup.ts と同じ）。
const endSessionEndpoint =
  "http://127.0.0.1:8081/realms/spring-modulith/protocol/openid-connect/logout?";

const logoutButton = (page: Page): Locator => page.getByRole("button", { name: "ログアウト" });
const homeHeading = (page: Page): Locator =>
  page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" });
// hidden input は role を持たないため、規約の例外として locator で取る。
const csrfInput = (page: Page): Locator => page.locator('input[name="_csrf"]');

async function logIn(page: Page): Promise<void> {
  await page.goto("/oauth2/authorization/web");
  await signInOnKeycloak(page);
  await expect(page).toHaveURL("http://localhost:5173/");
}

async function apiStatus(page: Page): Promise<number> {
  const response = await page.request.get("/api/missing");
  return response.status();
}

async function expectLoggedOutPage(page: Page): Promise<void> {
  await expect(page).toHaveURL("http://localhost:5173/logged-out");
  await expect(page.getByRole("heading", { level: 1, name: "ログアウトしました" })).toBeVisible();
}

// 改変したフォームで送ると 403 になり、ログアウトせずに認証済みのまま残ることを確かめる。
// 改変が送信に届いたことを呼び出し側で確かめられるよう、送った _csrf の値を返す。
async function expectLogoutRejected(page: Page): Promise<string | null> {
  const logoutResponse = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" && new URL(response.url()).pathname === "/logout",
  );
  await logoutButton(page).click();
  const response = await logoutResponse;
  expect(response.status()).toBe(403);
  const submittedToken = new URLSearchParams(response.request().postData() ?? "").get("_csrf");

  await page.goto("/");
  await expect(homeHeading(page)).toBeVisible();
  expect(await apiStatus(page)).toBe(404);
  return submittedToken;
}

async function xsrfToken(context: BrowserContext): Promise<string> {
  const cookies = await context.cookies();
  const token = cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value;
  // undefined を送って別の理由で 403 になるのを防ぐ。
  if (token === undefined || token === "") {
    throw new Error("XSRF-TOKEN Cookie がありません");
  }
  return token;
}

let cspMessages: string[] = [];

test.beforeEach(({ page }) => {
  cspMessages = [];
  // ログアウトのフォームは IdP へ redirect されるため、form-action の違反は console に出る。
  // Keycloak の画面のメッセージも区別せずに集め、隠さない。
  page.on("console", (message) => {
    if (message.text().includes("Content Security Policy")) {
      cspMessages.push(message.text());
    }
  });
});

test.afterEach(() => {
  expect(cspMessages).toEqual([]);
});

test("ログイン直後に、ほかの操作をせずにログアウトすると /logged-out へ移る", async ({ page }) => {
  await logIn(page);

  const endSession = page.waitForRequest((request) => request.url().startsWith(endSessionEndpoint));
  await logoutButton(page).click();
  const request = await endSession;

  expect(new URL(request.url()).searchParams.get("post_logout_redirect_uri")).toBe(
    "http://localhost:5173/logged-out",
  );
  await expectLoggedOutPage(page);
  await expect(page).toHaveTitle("デモアプリケーション");
});

test("ログアウト後はアプリと SSO のセッションが終わり、もう一度ログインして戻れる", async ({
  page,
}) => {
  await logIn(page);
  await logoutButton(page).click();
  await expectLoggedOutPage(page);
  expect(await apiStatus(page)).toBe(401);

  // Keycloak の SSO セッションが終わっていれば、資格情報の入力を求められる。
  await page.getByRole("link", { name: "もう一度ログイン" }).click();
  await expect(page.getByLabel("Username or email")).toBeVisible();
  await signInOnKeycloak(page);

  await expect(page).toHaveURL("http://localhost:5173/");
  await expect(homeHeading(page)).toBeVisible();
  expect(await apiStatus(page)).toBe(404);
});

test("_csrf を削除して送るとログアウトを拒否する", async ({ page }) => {
  await logIn(page);
  await csrfInput(page).evaluate((input) => {
    input.remove();
  });

  expect(await expectLogoutRejected(page)).toBeNull();
});

// csrf.spa() はフォームの _csrf を XorCsrfTokenRequestAttributeHandler で URL-safe Base64 として復号する。
// マスクしない XSRF-TOKEN の値（36 文字の UUID）は 27 バイトになり、必要な 72 バイトと一致しないため token は null になる。
test("_csrf にマスクしない Cookie の値を入れて送るとログアウトを拒否する", async ({
  page,
  context,
}) => {
  await logIn(page);
  const token = await xsrfToken(context);
  await csrfInput(page).evaluate((input: HTMLInputElement, value) => {
    input.value = value;
  }, token);

  // token を送らなかったのではなく、マスクしない値を送って拒否されたことを確かめる。
  expect(await expectLogoutRejected(page)).toBe(token);
});

// AppShell の header で「ログアウト」が最初に focus を受け取る要素であることに依存する。
// header の前に focus を受け取る要素を足したときは、Tab の回数を見直す。
test("keyboard だけでログアウトできる", async ({ page }) => {
  await logIn(page);
  await expect(homeHeading(page)).toBeVisible();

  await page.keyboard.press("Tab");
  await expect(logoutButton(page)).toBeFocused();
  await page.keyboard.press("Enter");

  await expectLoggedOutPage(page);
});
