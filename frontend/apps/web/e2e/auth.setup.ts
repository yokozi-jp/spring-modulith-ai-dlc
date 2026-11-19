import type { BrowserContext, Page } from "@playwright/test";

import { authFile, chooseSeededTenant, signInOnKeycloak } from "./environment";
import { expect, test as setup } from "./fixtures";

async function sessionCookie(context: BrowserContext): Promise<string | undefined> {
  const cookies = await context.cookies();
  return cookies.find(({ name }) => name === "APP_SESSION")?.value;
}

// 戻り先がテナントを選ぶ画面であることと、認証の前後で APP_SESSION の値が変わったことで、ログインの成立を確かめる。
async function logIn(page: Page, context: BrowserContext): Promise<string | undefined> {
  // storageState を持たない context で、認証の開始から始める。
  await page.goto("/oauth2/authorization/web");
  await expect(page).toHaveURL(/^http:\/\/127\.0\.0\.1:8081\//u);
  // 認証開始の時点で authorization request を保存する session が作られ、APP_SESSION が発行される。
  const sessionBeforeLogin = await sessionCookie(context);

  await signInOnKeycloak(page);

  // 認証の成立時に Spring Security が session ID を変える。
  await expect(page).toHaveURL("http://localhost:5173/tenants");
  const sessionAfterLogin = await sessionCookie(context);
  expect(sessionAfterLogin).toBeDefined();
  expect(sessionAfterLogin).not.toBe(sessionBeforeLogin);
  return sessionAfterLogin;
}

setup(
  "Keycloak の画面から test-user でログインし、テナントを選んでトップページへ戻る",
  async ({ page, context }) => {
    const sessionAfterLogin = await logIn(page, context);

    await chooseSeededTenant(page);

    await expect(page.getByText("テナント：サンプル商事")).toBeVisible();
    const sessionAfterSelection = await sessionCookie(context);
    expect(sessionAfterSelection).toBeDefined();
    expect(sessionAfterSelection).not.toBe(sessionAfterLogin);
    await context.storageState({ path: authFile });
  },
);
