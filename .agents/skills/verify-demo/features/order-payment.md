# 注文の確定と決済

利用者は下書きの注文を詳細の画面で確定する。
確定すると backend の Listener が決済代行へ請求し、詳細の画面の「決済」の欄が結果（決済済みと金額、拒否、再投入待ち）を表示する。

## Sub-features

- `pay-confirm`：「確定する」で注文を確定済みにし、「注文を確定しました。」を出す。
- `pay-success`：決済代行が成功すると、欄が「決済済み」と請求した金額を出し、決済記録が 1 行でき、請求は 1 回である。
- `pay-declined`：決済代行が拒否すると、欄が「決済代行が決済を拒否しました。」を出す。
- `pay-transient`：決済代行が 5xx かタイムアウトのとき、欄は 15 秒まで「決済の結果を確かめています。」を出し、その後に再投入待ちの文言と「決済の状態を読み直す」ボタンを出す。決済記録はできない。
- `pay-seeded`：代表の注文 `SEED-C03`（決済済み）、`SEED-C05`（拒否）、`SEED-C06`（決済記録なし）が、それぞれの文言を出す。

## How to get to it (user POV)

- 「注文を作成する」で下書きを作り、詳細の画面で「確定する」を押す。
- 注文の一覧の「`<客先注文番号>` の詳細」から、下書きの注文の詳細を開いて「確定する」を押す。
- 確定済みの注文の詳細を開き、「決済」の欄を読む（`pay-seeded`）。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である。
- 販売中（`ON_SALE`）の商品が 1 つ以上ある。

- **確定と決済済み（`pay-confirm`、`pay-success`）。** 画面で作成して確定する。`node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-payment.mjs quantity=2` を実行する。`passed` が出て、`03-confirmed.png` に「注文を確定しました。」、`04-paid.png` に「決済済み」と `単価 × 2` の円の金額が写る。`result.json` の `proof.row` が `PAID,<金額>,CONFIRMED`、`proof.charges` が `1` である。
- **一覧からの入口。** 同じ journey の最後の `05-listed.png` に、作った注文の「`<客先注文番号>` の詳細」リンクがある。一覧から開いて確定する入口は、`page.getByRole("link", { name: \`${code} の詳細\` })` を押してから「確定する」を押す journey で確かめる。
- **拒否（`pay-declined`）。** 作成の後、確定の前に `const restore = await gateway.failChargesFor(orderId, { status: 201, headers: { "Content-Type": "application/json" }, jsonBody: { chargeId: \`ch_${orderId}\`, status: "DECLINED" } })` を足し、確定する。`region` 名「決済」の `status` が「決済代行が決済を拒否しました。」になる。最後に `restore()` を呼ぶ。
- **一時障害（`pay-transient`）。** 確定の前に `gateway.failChargesFor(orderId, { status: 503 })` を足す。`expect.poll(() => gateway.chargeCount(orderId)).toBeGreaterThanOrEqual(1)` の後、欄が 30 秒以内に「決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。」になる。`sql` で `payment.t_payment` にその注文の行がない。スタブを取り除いてから「決済の状態を読み直す」を押しても文言は変わらず、請求は 1 回のままである。
- **代表の注文（`pay-seeded`）。** `/orders?status=CONFIRMED` で「SEED-C03 の詳細」「SEED-C05 の詳細」「SEED-C06 の詳細」を順に開く。欄はそれぞれ「決済済み」「決済代行が決済を拒否しました。」「決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。」を出す。

## Gotchas

- 決済の結果は非同期で入る。確定の直後に欄を読まず、`toHaveText` で最大 30 秒待つ。画面を読み直すと 15 秒の読み直しの期間が最初からになる。
- 検証スタックの backend は `.env.test` で定期の再投入を無効にしている。一時障害の後に成功へ戻しても決済済みにならないのが正しい結果である。再投入の後の決済記録は E2E と同じく、この検証の範囲の外（`PaymentGatewayClientIntegrationTest`）にある。
- タイムアウトは `{ status: 201, ..., fixedDelayMilliseconds: 3000 }` で作る。backend の Client の `read-timeout`（2 秒、`application.yaml`）より長くする。
- 拒否と一時障害のスタブは注文 ID（冪等性キー）で絞る。journey が失敗しても取り除けるよう、`try` と `finally` で `restore()` を呼ぶ。
- 決済代行の共有のスタブは応答に金額を返さない。`t_payment.charged_amount_jpy` は backend が請求した金額なので、画面の金額に加えて `proof.row` の金額も合計と比べる。
