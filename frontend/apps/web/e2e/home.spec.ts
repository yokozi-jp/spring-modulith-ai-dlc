import { expect, test } from "./fixtures";

test("認証済みのトップページで見出しを確かめ、カウントを増やせる", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveTitle("デモアプリケーション");
  await expect(page.locator("html")).toHaveAttribute("lang", "ja");
  await expect(page.getByRole("banner")).toBeVisible();
  await expect(
    page.getByRole("main").getByRole("heading", { level: 1, name: "はじめる" }),
  ).toBeVisible();

  await page.getByRole("button", { name: "カウント：0" }).click();
  await expect(page.getByRole("button", { name: "カウント：1" })).toBeVisible();
});
