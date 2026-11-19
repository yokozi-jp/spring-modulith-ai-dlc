# 注文の一覧と絞り込み

利用者は「注文」の画面で注文の一覧（客先注文番号、状態、合計金額）を見て、状態（下書き、確定済み、取消済み）で絞り込み、各注文の詳細を開く。

## Sub-features

- `list-all`：`/orders` が代表の注文 6 件と、検証で作った注文を表示する。
- `list-filter`：「状態で絞り込む」のリンクで一覧を絞り、選んだリンクだけに `aria-current="page"` が付く。
- `list-empty`：該当する注文がないとき「該当する注文はありません。」を出す。
- `list-detail`：「`<客先注文番号>` の詳細」のリンクで詳細の画面（見出し「注文 `<客先注文番号>`」）を開く。

## How to get to it (user POV)

- ブラウザで `/orders` を開く。
- 詳細の画面の「注文の一覧へ戻る」を押す。
- 絞り込みの状態は URL の `?status=DRAFT`、`?status=CONFIRMED`、`?status=CANCELLED` でも開ける。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である（代表の注文 6 件）。

- **一覧（`list-all`）。** `page.goto("/orders")` で開く。`heading`（level 1、「注文」）が見え、`link`「SEED-C01 の詳細」から「SEED-C06 の詳細」が見える。`step("all")` で残す。
- **絞り込み（`list-filter`）。** `page.getByRole("navigation", { name: "状態で絞り込む" }).getByRole("link", { name: "確定済み" }).click()` を押す。URL が `?status=CONFIRMED` になり、「SEED-C03 の詳細」「SEED-C05 の詳細」「SEED-C06 の詳細」だけが見え、「確定済み」のリンクが `aria-current="page"` を持つ。「下書き」と「取消済み」でも同じく確かめ、`sql("select order_status_typ, count(*) from ordering.t_order group by 1")` の件数と、画面の行数を比べる。
- **詳細（`list-detail`）。** 「SEED-C03 の詳細」を押す。`heading`「注文 SEED-C03」が見える。「注文の一覧へ戻る」で `/orders` に戻る。
- **空の一覧（`list-empty`）。** 代表データの状態では、どの絞り込みにも 1 件以上の注文がある。`list-empty` は空の状態を作れないため、この検証スタックでは確かめられない（`Gotchas`）。

## Gotchas

- 代表データはどの状態にも注文を持つため、`list-empty` は検証スタックで届かない。届かない入口として報告し、ほかの入口で確かめたことにしない。
- 並列の検証や E2E が作った注文も一覧に出る。行数で確かめるときは、同じ時点の `sql` の件数と比べる。
- 「すべて」のリンクは `status` がないときだけ選択中になる。`?status=` の空の値で開いたときの見え方は確かめていない。
