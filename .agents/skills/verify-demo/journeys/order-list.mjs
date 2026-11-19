// 注文の一覧、状態の絞り込み、詳細への移動と一覧への戻りを確かめる。
// features/order-list.md の `list-all`、`list-filter`、`list-detail`。`list-empty` は代表データでは届かない。
const statuses = { DRAFT: "下書き", CONFIRMED: "確定済み", CANCELLED: "取消済み" };
const seededByStatus = {
  DRAFT: ["SEED-C01", "SEED-C02"],
  CONFIRMED: ["SEED-C03", "SEED-C05", "SEED-C06"],
  CANCELLED: ["SEED-C04"],
};

export default async function ({ page, expect, step, sql, record }) {
  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "注文" })).toBeVisible();
  for (const code of Object.values(seededByStatus).flat()) {
    await expect(page.getByRole("link", { name: `${code} の詳細` })).toBeVisible();
  }
  await step("all");

  const filter = page.getByRole("navigation", { name: "状態で絞り込む" });
  const counts = {};
  for (const [status, label] of Object.entries(statuses)) {
    await filter.getByRole("link", { name: label }).click();
    await expect(page).toHaveURL(new RegExp(`\\?status=${status}$`, "u"));
    await expect(filter.getByRole("link", { name: label })).toHaveAttribute("aria-current", "page");
    for (const [other, codes] of Object.entries(seededByStatus)) {
      for (const code of codes) {
        await expect(page.getByRole("link", { name: `${code} の詳細` })).toHaveCount(other === status ? 1 : 0);
      }
    }
    const rows = await page.getByRole("table").getByRole("row").count();
    const stored = sql("select count(*) from ordering.t_order where order_status_typ = :'status'", { vars: { status } });
    counts[status] = { rows: rows - 1, stored: Number(stored) };
    expect(rows - 1, `${status} の行数`).toBe(Number(stored));
    await step(`filter-${status}`);
  }
  record("counts", counts);

  await filter.getByRole("link", { name: "すべて" }).click();
  await expect(page).toHaveURL(/\/orders$/u);
  await expect(filter.getByRole("link", { name: "すべて" })).toHaveAttribute("aria-current", "page");
  await page.getByRole("link", { name: "SEED-C03 の詳細" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "注文 SEED-C03" })).toBeVisible();
  await step("detail");
  await page.getByRole("link", { name: "注文の一覧へ戻る" }).click();
  await expect(page).toHaveURL(/\/orders$/u);
  await expect(page.getByRole("heading", { level: 1, name: "注文" })).toBeVisible();
  await step("back");
}
