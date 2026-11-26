import type { Locator, Page } from "@playwright/test";

import { webOrigin } from "./environment";
import { expect } from "./fixtures";

export function logoutButton(page: Page): Locator {
  return page.getByRole("button", { name: "ログアウト" });
}

export function homeHeading(page: Page): Locator {
  return page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" });
}

export function notFoundHeading(page: Page): Locator {
  return page.getByRole("heading", { level: 1, name: "ページが見つかりません" });
}

export function tenantsHeading(page: Page): Locator {
  return page.getByRole("heading", { level: 1, name: "テナントを選んでください" });
}

export function membersForbiddenHeading(page: Page): Locator {
  return page.getByRole("heading", { level: 1, name: "権限がありません" });
}

export function loginFailedHeading(page: Page): Locator {
  return page.getByRole("heading", { level: 1, name: "ログインできませんでした" });
}

export function sessionExpiredHeading(page: Page): Locator {
  return page.getByRole("heading", { level: 1, name: "ログインの有効期限が切れました" });
}

// 見出しは読み込み時の認証の結果にすぎない。今もセッションが有効なことは、/api/missing が 404（401 でない）で確かめる。
export async function expectAuthenticatedHome(page: Page): Promise<void> {
  await expect(page).toHaveURL(`${webOrigin}/`);
  await expect(homeHeading(page)).toBeVisible();
  const response = await page.request.get("/api/missing");
  expect(response.status()).toBe(404);
}

export async function expectLoggedOutPage(page: Page): Promise<void> {
  await expect(page).toHaveURL(`${webOrigin}/logged-out`);
  await expect(page.getByRole("heading", { level: 1, name: "ログアウトしました" })).toBeVisible();
}

// load を待つと、SPA が 401 を受けて始めるログインへの遷移と競合する。
export async function openExpectingLogin(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: "commit" });
  await expect(page.getByLabel("Username or email")).toBeVisible();
}
