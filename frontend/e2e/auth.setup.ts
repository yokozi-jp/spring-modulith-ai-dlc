import type { BrowserContext } from "@playwright/test";
import { expect, test as setup } from "@playwright/test";

import { authFile, signInOnKeycloak } from "./environment";

async function sessionCookie(context: BrowserContext): Promise<string | undefined> {
  const cookies = await context.cookies();
  return cookies.find(({ name }) => name === "APP_SESSION")?.value;
}

setup(
  "Keycloak の画面から test-user でログインして認証済みのトップページへ戻る",
  async ({ page, context }) => {
    // storageState を持たない context で開く。/ は preview が配信するため、ここでは認証を確かめない。
    await page.goto("/");

    await page.goto("/oauth2/authorization/web");
    await expect(page).toHaveURL(/^http:\/\/127\.0\.0\.1:8081\//u);
    // 認証開始の時点で authorization request を保存する session が作られ、APP_SESSION が発行される。
    const sessionBeforeLogin = await sessionCookie(context);

    await signInOnKeycloak(page);

    // 認証の成立時に Spring Security が session ID を変えるため、値の変化で認証済みを確かめる。
    await expect(page).toHaveURL("http://localhost:5173/");
    const sessionAfterLogin = await sessionCookie(context);
    expect(sessionAfterLogin).toBeDefined();
    expect(sessionAfterLogin).not.toBe(sessionBeforeLogin);
    await context.storageState({ path: authFile });
  },
);
