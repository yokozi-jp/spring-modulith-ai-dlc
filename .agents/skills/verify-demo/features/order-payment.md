# 注文の確定と決済

利用者は下書きの注文を詳細の画面で確定する。
確定すると backend の Listener が決済代行へ請求し、詳細の画面の「決済」の欄が結果（決済済みと金額、拒否、再投入待ち）を表示する。

## Sub-features

- `pay-confirm`：「確定する」で注文を確定済みにし、「注文を確定しました。」を出す。
- `pay-success`：決済代行が成功すると、欄が「決済済み」と請求した金額を出し、決済記録が 1 行でき、請求は 1 回である。
- `pay-declined`：決済代行が拒否すると、欄が「決済代行が決済を拒否しました。」を出す。
- `pay-transient`：決済代行が 5xx かタイムアウトのとき、欄は確定から 15 秒まで「決済の結果を確かめています。」を出し、その後に再投入待ちの文言と「決済の状態を読み直す」ボタンを出す。
  決済記録はできない。
- `pay-seeded`：代表の注文 `SEED-C03`（決済済み）、`SEED-C05`（拒否）、`SEED-C06`（決済記録なし）が、それぞれの文言を出す。

## How to get to it (user POV)

- 「注文を作成する」で下書きを作り、詳細の画面で「確定する」を押す。
- 注文の一覧の「`<客先注文番号>` の詳細」から下書きの注文の詳細を開き、「確定する」を押す。
- 確定済みの注文の詳細を開き、「決済」の欄を読む（`pay-seeded`）。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である。
- 販売中（`ON_SALE`）の商品が 1 つ以上ある。

`journeys/order-payment.mjs` が `pay-confirm` と `pay-success` を、`journeys/payment-failures.mjs` が `pay-declined`、`pay-transient`、`pay-seeded` を実行する。
`payment-failures.mjs` は `cases=declined,status,delay,seeded` で一部だけを選べる。

- **確定と決済済み（`pay-confirm`、`pay-success`）。**
  `node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-payment.mjs quantity=2` を実行する。
  `passed` が出る。
  `03-confirmed.png` に「注文を確定しました。」が、`04-paid.png` に「決済済み」と `単価 × 2` の円の金額が写る。
  `result.json` の `records.proof.row` が `PAID|<金額>|CONFIRMED`、`records.proof.charges` が `1` である。
- **一覧からの入口。**
  同じ journey の最後の `05-listed.png` に、作った注文の「`<客先注文番号>` の詳細」リンクがある。
  一覧から開いて確定する入口は、`payment-failures.mjs` の各ケースが通る。
  下書きを作った後に `/orders?status=DRAFT` の「`<客先注文番号>` の詳細」を押し、開いた詳細の画面で「確定する」を押す。
- **拒否（`pay-declined`）。**
  作成の後、確定の前に `const restore = await gateway.failChargesFor(orderId, { kind: "declined" })` を足し、確定する。
  `detail.paymentStatus` が `detail.paymentTextTimeoutMs` までに「決済代行が決済を拒否しました。」になる（`detail` は `screensOf(page).detail`）。
  最後に `restore()` を呼ぶ。
- **一時障害（`pay-transient`）。**
  確定の前に `gateway.failChargesFor(orderId, { kind: "status", status: 503 })` を足す。
  タイムアウトは `{ kind: "delay", milliseconds: 3000 }` で作る。
  `expect.poll(() => gateway.chargeCount(orderId)).toBeGreaterThanOrEqual(1)` の後、`detail.paymentStatus` で再投入待ちの文言（「決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。」）を待つ。
  `orders.listPayments(page.request, orderId)` が空で、`sql("select count(*) from payment.t_payment where order_public_id = :'orderId'", { orderId })` が `0` である。
  スタブを取り除いてから「決済の状態を読み直す」を押すと、欄は 15 秒ほど「決済の結果を確かめています。」に戻り、ボタンが消える。
  その後に再投入待ちの文言に戻り、請求は 1 回のままである。
- **代表の注文（`pay-seeded`）。**
  `/orders?status=CONFIRMED` で「SEED-C03 の詳細」「SEED-C05 の詳細」「SEED-C06 の詳細」を順に開く。
  欄はそれぞれ「決済済み」「決済代行が決済を拒否しました。」と、再投入待ちの文言を出す。

## Gotchas

- 決済の結果は非同期で入る。
  確定の直後に欄を読まず、`detail.paymentStatus` が変わるのを `detail.paymentTextTimeoutMs`（30 秒）まで待つ。
  画面を読み直すか「決済の状態を読み直す」を押すと、15 秒の読み直しの期間が最初からになる。
- 一時障害の後に成功へ戻しても決済済みにならないのが、検証スタックでの正しい結果である。
  理由と、再投入の後の決済記録を確かめる場所は `docs/e2e/testing-strategy.md` の「E2E で確かめない範囲」にある。
- タイムアウトの 3000 ミリ秒は、backend の Client の `read-timeout`（2 秒、`application.yaml`）より長くしてある。
- 拒否と一時障害のスタブは注文ごとに足すので、journey が失敗しても取り除けるよう `try` と `finally` で `restore()` を呼ぶ。
- 決済代行の共有のスタブは応答に金額を返さない。
  `t_payment.charged_amount_jpy` は backend が請求した金額なので、画面の金額に加えて `records.proof.row` の金額も合計と比べる。
