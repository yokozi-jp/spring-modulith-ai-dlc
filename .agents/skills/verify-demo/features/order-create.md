# 注文の作成

利用者は「注文の作成」の画面で客先注文番号と明細（商品と数量）を入力し、「作成する」で下書きの注文を作る。
作ると詳細の画面へ移り、状態は「下書き」になる。

## Sub-features

- `create-draft`：客先注文番号と明細 1 行で「作成する」を押すと、`/orders/<注文 ID>` の詳細の画面へ移り、状態が「下書き」になる。
- `create-lines`：「明細を追加する」で行を足し、「明細 `<番号>` を削除する」で行を消せる。
- `create-validation`：空の客先注文番号、商品の未選択、範囲外の数量で、欄の下に誤りの文言を出し、作成しない。
- `create-duplicate`：使用済みの客先注文番号で作ると、「この客先注文番号は既に使われている可能性があります。…」を出す。
- `create-csrf`：画面の作成は `X-XSRF-TOKEN` を付けて成功し、token のない `POST /api/orders` は 403 になる。

## How to get to it (user POV)

- 注文の一覧の「注文を作成する」リンクを押す。
- ブラウザで `/orders/new` を開く。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である。
- 販売中の商品が 1 つ以上ある（`GET /api/products` の `salesStatus` が `ON_SALE`）。

- **下書きの作成（`create-draft`）。** `journeys/order-payment.mjs` の確定の前までと同じ手順で作る。`02-draft.png` で見出し「注文 `<客先注文番号>`」と状態「下書き」を確かめ、`sql("select order_status_typ from ordering.t_order where public_id = '<注文 ID>'")` が `DRAFT` を返す。
- **明細の追加と削除（`create-lines`）。** 「明細を追加する」を押すと、`group`「明細 2」が増える。「明細 2 を削除する」を押すと消える。2 行で作ると、詳細の明細の表に 2 行出る。
- **入力の誤り（`create-validation`）。** 何も入力せず「作成する」を押す。「客先注文番号を入力してください。」と「商品を選んでください。」が見え、URL は `/orders/new` のままで、`network.log` に `POST /api/orders` がない。数量に `0` を入れると「数量は … 以上 … 以下の整数にしてください。」が見える。
- **重複（`create-duplicate`）。** 客先注文番号に `SEED-C01` を入れて作る。作成の失敗の文言が見え、`sql("select count(*) from ordering.t_order where customer_order_code = 'SEED-C01'")` が `1` のままである。
- **CSRF（`create-csrf`）。** 画面の作成の `network.log` に `POST /api/orders 201` がある。`page.request.post("/api/orders", { data: ... })`（token なし）は 403 を返し、その客先注文番号の行は `sql` でできていない。

## Gotchas

- 作成の画面は空の明細を 1 行持って開く。「明細を追加する」を押してから 1 行目を埋めると、空の 2 行目で誤りになる。
- 商品の選択肢の文言は商品名である。販売終了の商品は「`<商品名>`（販売終了）」になり、作成は 422 で拒まれる。
- 客先注文番号は 30 文字以下である。`VERIFY-` に UUID を丸ごと付けると超える。
- `create-duplicate` の 409 は重複と断定できないため、欄に `aria-invalid` を付けない。欄の誤りの表示を期待しない。
