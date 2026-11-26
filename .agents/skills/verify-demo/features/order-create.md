# 注文の作成

利用者は「注文の作成」の画面で客先注文番号と明細（商品と数量）を入力し、「作成する」で下書きの注文を作る。
作ると詳細の画面へ移り、状態は「下書き」になる。

## Sub-features

- `create-draft`：客先注文番号と明細 1 行で「作成する」を押すと、`/orders/<注文 ID>` の詳細の画面へ移り、状態が「下書き」になる。
- `create-lines`：「明細を追加する」で行を足し、「明細 `<番号>` を削除する」で行を消せる。
- `create-validation`：空の客先注文番号、商品の未選択、範囲外の数量で、欄の下に誤りの文言を出し、作成しない。
- `create-duplicate`：使用済みの客先注文番号で作ると、「この客先注文番号は既に使われている可能性があります。…」を出す。
- `create-on-sale-only`：商品の選択肢は販売中の商品だけで、販売終了の商品を出さない。
- `create-csrf`：画面の作成は `X-XSRF-TOKEN` を付けて成功し、token のない `POST /api/v1/orders` は 403 になる。

## How to get to it (user POV)

- 注文の一覧の「注文を作成する」リンクを押す。
- ブラウザで `/orders/new` を開く。

## Driving it with drive.mjs

Preconditions:

- `stack.sh doctor` がすべて OK である。
- 販売中の商品が 2 つ以上、販売終了の商品が 1 つある（代表データのとおり）。

`node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-create.mjs` が、次のすべてを順に実行する。

- **下書きの作成（`create-draft`）。**
  明細 2 行で作ると、詳細の画面へ移る。
  `draft` の screenshot で見出し「注文 `<客先注文番号>`」と状態「下書き」を確かめる。
  `sql("select order_status_typ from ordering.t_order where public_id = :'orderId'", { orderId })` が `DRAFT` を返す。
- **明細の追加と削除（`create-lines`）。**
  「明細を追加する」を押すと、`group`「明細 2」が増える。
  「明細 2 を削除する」を押すと消える。
  2 行で作ると、詳細の明細の表に 2 行出る。
- **入力の誤り（`create-validation`）。**
  何も入力せず「作成する」を押す。
  「客先注文番号を入力してください。」と「商品を選んでください。」が見え、URL は `/orders/new` のままで、`records.proof.validationPosts` が空である。
  `/orders/new` を開き直し、客先注文番号と商品を正しく入れ、数量に `0` を入れて「作成する」を押すと、「数量は … 以上 … 以下の整数にしてください。」が見える。
- **重複（`create-duplicate`）。**
  客先注文番号に `SEED-C01` を入れて作る。
  作成の失敗の文言が見える。
  `sql("select count(*) from ordering.t_order where customer_order_code = 'SEED-C01'")` が `1` のままである。
- **販売中の商品だけ（`create-on-sale-only`）。**
  `helpers.listProducts(page.request)` で `salesStatus` が `DISCONTINUED` の商品を 1 つ選ぶ。
  `/orders/new` の `getByLabel("商品")` に、その商品名の `option` が 0 件で、販売中の商品名の `option` が 1 件ある。
- **CSRF（`create-csrf`）。**
  画面の作成の `network.log` に `POST /api/v1/orders 201` がある。
  `page.request.post("/api/v1/orders", { data: ... })`（token なし）は 403 を返し、その客先注文番号の行は `sql` でできていない。

## Gotchas

- 作成の画面は空の明細を 1 行持って開く。
  「明細を追加する」を押してから 1 行目を埋めると、空の 2 行目で誤りになる。
- 「`<商品名>`（販売終了）」のラベルは、販売終了の商品を既に持つ行を明細の変更のフォームで開いたときだけ出る。
  作成の画面には出ないので、作成の画面から販売終了の商品の 422 には届かない。
- 客先注文番号は 30 文字以下である。
  `helpers.uniqueCode` は接頭辞に 19 文字を足すので、接頭辞を 11 文字以下にする。
- 誤りを出したフォームで別の欄を誤りにして「作成する」を押しても、新しい誤りは出ない。
  空の客先注文番号のまま数量を `0` にして押したとき、数量の誤りは出なかった。
  欄ごとの誤りは、開き直した画面で 1 つずつ確かめる。
- `create-duplicate` の 409 は重複と断定できないため、欄に `aria-invalid` を付けない。
  欄の誤りの表示を期待しない。
