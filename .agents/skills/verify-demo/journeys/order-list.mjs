// 注文の一覧、状態の絞り込み、詳細への移動と一覧への戻りを確かめる。
// features/order-list.md の `list-all`、`list-filter`、`list-detail`。`list-empty` は代表データでは届かない。
const statusByLabel = { 下書き: "DRAFT", 確定済み: "CONFIRMED", 取消済み: "CANCELLED" };

export default async function ({ page, expect, step, sql, record, helpers, screensOf }) {
  const { list, detail } = screensOf(page);
  const seededByLabel = helpers.seededOrderCodesByStatusLabel;
  await list.goto();
  await expect(list.heading).toBeVisible();
  for (const code of Object.values(seededByLabel).flat()) {
    await expect(list.detailLink(code)).toBeVisible();
  }
  await step("all");

  const counts = {};
  for (const [label, status] of Object.entries(statusByLabel)) {
    await list.statusLink(label).click();
    await expect(page).toHaveURL(new RegExp(`\\?status=${status}$`, "u"));
    await expect(list.statusLink(label)).toHaveAttribute("aria-current", "page");
    for (const [otherLabel, codes] of Object.entries(seededByLabel)) {
      for (const code of codes) {
        await expect(list.detailLink(code)).toHaveCount(otherLabel === label ? 1 : 0);
      }
    }
    const rows = await list.rows.count();
    const stored = sql("select count(*) from ordering.t_order where order_status_typ = :'status'", { status });
    counts[status] = { rows: rows - 1, stored: Number(stored) };
    expect(rows - 1, `${status} の行数`).toBe(Number(stored));
    await step(`filter-${status}`);
  }
  record("counts", counts);

  await list.statusLink("すべて").click();
  await expect(page).toHaveURL(/\/orders$/u);
  await expect(list.statusLink("すべて")).toHaveAttribute("aria-current", "page");
  await list.detailLink("SEED-C03").click();
  await expect(detail.heading("SEED-C03")).toBeVisible();
  await step("detail");
  await detail.listLink.click();
  await expect(page).toHaveURL(/\/orders$/u);
  await expect(list.heading).toBeVisible();
  await step("back");
}
