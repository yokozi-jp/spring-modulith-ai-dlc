// 業務データが空の検証スタック（stack.sh up --empty）で、一覧と各絞り込みが空の表示を出すことを確かめる。
// features/order-list.md の `list-empty`。代表データを入れたスタックでは前提の確認で失敗する。
const filters = { すべて: "", 下書き: "?status=DRAFT", 確定済み: "?status=CONFIRMED", 取消済み: "?status=CANCELLED" };

export default async function ({ page, expect, step, sql, record }) {
  const stored = sql("select count(*) from ordering.t_order", { role: "ordering_module" });
  if (stored !== "0") {
    throw new Error(`注文が ${stored} 件あります。stack.sh up --empty で起動したスタックで実行してください。`);
  }

  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "注文" })).toBeVisible();
  const filter = page.getByRole("navigation", { name: "状態で絞り込む" });
  for (const [label, search] of Object.entries(filters)) {
    await filter.getByRole("link", { name: label }).click();
    await expect(page).toHaveURL(new RegExp(`/orders${search.replace("?", "\\?")}$`, "u"));
    await expect(filter.getByRole("link", { name: label })).toHaveAttribute("aria-current", "page");
    await expect(page.getByText("該当する注文はありません。")).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
    await step(`empty-${search === "" ? "ALL" : search.slice("?status=".length)}`);
  }
  // 空の一覧からも作成の画面へ進める。
  await page.getByRole("link", { name: "注文を作成する" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "注文の作成" })).toBeVisible();
  await step("create-from-empty");
  record("storedOrders", Number(stored));
}
