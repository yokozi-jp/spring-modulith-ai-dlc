# 明細の変更と取り消し

利用者は下書きの注文の詳細の画面で「明細の変更」のフォームから数量と商品を変えて保存し、「取り消す」で注文を取消済みにする。
別のタブが先に保存していれば楽観的ロックの競合になり、画面は最新の内容と回復の選択肢を出す。

## Sub-features

- `edit-save`：「明細を保存する」で明細を変え、「明細を変更しました。」を出し、明細の表と合計金額が変わる。
- `edit-conflict-discard`：競合で「変更を捨てて最新を表示する」を選ぶと、ほかのタブの明細に戻り、続けて保存できる。
- `edit-conflict-reapply`：競合で「最新の版に変更を適用し直す」を選ぶと、自分の入力で保存し、ほかのタブでも見える。
- `edit-cancel`：「取り消す」で「注文を取り消しました。」を出し、状態が「取消済み」になり、決済の欄は「この注文は決済の対象ではありません。」を出す。
- `edit-locked`：確定済みか取消済みの注文には「明細の変更」のフォームと「確定する」「取り消す」が出ない。

## How to get to it (user POV)

- 注文の一覧の「`<客先注文番号>` の詳細」から下書きの注文を開く。
- 作成の直後の詳細の画面。
- 同じ注文を 2 つ目のタブで開く（競合）。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である。
- journey が `orders.createOrder` で下書きを作る。
  代表の下書きの注文（`SEED-C01`、`SEED-C02`）を変更しない。

`node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-edit.mjs` が、次のすべてを順に実行する。

- **保存（`edit-save`）。**
  `page.getByRole("form", { name: "明細の変更" }).getByLabel("数量").fill("3")` の後、同じフォームの「明細を保存する」を押す。
  「明細を変更しました。」が見え、明細の表の 1 行目の数量が `3` になる。
  `sql("select l.ordered_count from ordering.t_order_line l join ordering.t_order o using (order_id) where o.public_id = :'orderId'", { orderId })` が `3` を返す。
- **競合と破棄（`edit-conflict-discard`）。**
  `const other = await context.newPage()` で同じ詳細を開き、`other` で数量 `2` を保存する。
  元のタブで数量 `3` を保存すると、「ほかの操作で注文が更新されました。上に最新の内容を表示しています。」と 2 つの選択肢が見える。
  `step("conflict")` の後に「変更を捨てて最新を表示する」を押すと、数量の欄が `2` になる。
  続けて `4` を保存できる。
- **競合と適用し直し（`edit-conflict-reapply`）。**
  同じく競合を起こし、「最新の版に変更を適用し直す」を押す。
  「明細を変更しました。」が見え、表が `3` になる。
  `other.reload()` の後、`other` の表も `3` になる。
- **取り消し（`edit-cancel`、`edit-locked`）。**
  「取り消す」を押す。
  「注文を取り消しました。」が見え、状態が「取消済み」になる。
  `form`「明細の変更」と、「確定する」「取り消す」のボタンが 0 件になる。
  `sql` で `order_status_typ` が `CANCELLED` である。
  別の下書きを「確定する」で確定しても、同じ 3 つが 0 件になる。

## Gotchas

- 競合は、元のタブが作成の直後の版を表示したまま保存したときに起きる。
  元のタブを読み直すと版が進み、競合にならない。
- 明細の表の数量は、`table` の 2 行目（見出しの行の次）の 3 列目にある（`getByRole("row").nth(1).getByRole("cell").nth(2)`）。
- `step(label, other)` で、2 つ目のタブの状態も残す。
  片方のタブの画面だけでは競合の証拠にならない。
- 取消済みの注文は戻せない。
  代表の注文を取り消すと、ほかの検証の前提を壊す。
