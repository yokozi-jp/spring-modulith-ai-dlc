# 注文の一覧と絞り込み

利用者は「注文」の画面で注文の一覧（客先注文番号、状態、合計金額）を見て、状態（下書き、確定済み、取消済み）で絞り込み、各注文の詳細を開く。

## Sub-features

- `list-all`：`/orders` が代表の注文 6 件と、検証で作った注文を表示する。
- `list-filter`：「状態で絞り込む」のリンクで一覧を絞り、選んだリンクだけに `aria-current="page"` が付く。
- `list-empty`：該当する注文がないとき、「該当する注文はありません。」を出す。
- `list-detail`：「`<客先注文番号>` の詳細」のリンクで、詳細の画面（見出し「注文 `<客先注文番号>`」）を開く。

## How to get to it (user POV)

- ブラウザで `/orders` を開く。
- 詳細の画面の「注文の一覧へ戻る」を押す。
- 絞り込みの状態は、URL の `?status=DRAFT`、`?status=CONFIRMED`、`?status=CANCELLED` でも開ける。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である（代表の注文 6 件）。

`node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-list.mjs` が、`list-empty` を除く次の手順を実行する。
`list-empty` は、`stack.sh up --empty` で起動した別のスタックで `journeys/order-list-empty.mjs` が実行する。

- **一覧（`list-all`）。**
  `page.goto("/orders")` で開く。
  `heading`（level 1、「注文」）が見え、`link`「SEED-C01 の詳細」から「SEED-C06 の詳細」が見える。
  `step("all")` で残す。
- **絞り込み（`list-filter`）。**
  `page.getByRole("navigation", { name: "状態で絞り込む" }).getByRole("link", { name: "確定済み" }).click()` で押す。
  URL が `?status=CONFIRMED` になり、「確定済み」のリンクが `aria-current="page"` を持つ。
  代表の注文のうち「SEED-C03 の詳細」「SEED-C05 の詳細」「SEED-C06 の詳細」が見え、「SEED-C01 の詳細」は見えない。
  「下書き」と「取消済み」でも同じく確かめる。
  画面の行数を、同じ時点の `sql("select count(*) from ordering.t_order where order_status_typ = 'CONFIRMED'")` と比べる。
- **詳細（`list-detail`）。**
  絞り込みの「すべて」を押して `/orders` に戻し、「SEED-C03 の詳細」を押す。
  `heading`「注文 SEED-C03」が見える。
  「注文の一覧へ戻る」で `/orders` に戻る。
- **空の一覧（`list-empty`）。**
  `stack.sh down` の後、`stack.sh up --empty` で代表データを入れずに起動し、`stack.sh doctor` で注文が 0 件であることを確かめる。
  `node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-list-empty.mjs` を実行する。
  「すべて」「下書き」「確定済み」「取消済み」のどれを選んでも、「該当する注文はありません。」が見え、表がない。
  空の一覧の「注文を作成する」から、作成の画面へ進める。

## Gotchas

- 代表データはどの状態にも注文を持つため、`list-empty` は代表データを入れたスタックでは届かない。
  `order-list-empty.mjs` は、注文が 1 件でもあれば前提の確認で失敗する。
- `--empty` のスタックには商品も代表の注文もないので、ほかの journey は失敗する。
  `list-empty` を確かめたら `stack.sh down` で片付け、ほかの journey は `stack.sh up` で起動し直したスタックで実行する。
- 同じスタックの journey が作った注文も一覧に出る。
  代表の注文以外の行があるのは正しい。
  行数で確かめるときは、同じ時点の `sql` の件数と比べる。
- 絞り込んだ一覧には、ほかの状態の代表の注文のリンクがない。
  絞り込みの後に別の状態の注文を開くときは、先に「すべて」を押す。
- 「すべて」のリンクは `status` がないときだけ選択中になる。
  `?status=` の空の値で開いたときの見え方は確かめていない。
