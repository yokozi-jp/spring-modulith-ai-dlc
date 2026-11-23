import type { Locator, Page } from "@playwright/test";

import { webOrigin } from "./environment";
import { expect } from "./fixtures";

export function logoutButton(page: Page): Locator {
  return page.getByRole("button", { name: "ログアウト" });
}

export function homeHeading(page: Page): Locator {
  return page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" });
}

// 見出しは SPA の描画にすぎないため、/api/missing が 404（401 でない）であることでサーバ側の認証済みも確かめる。
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
