---
name: verify-demo
description: デモアプリケーション（React の SPA と Spring Boot の backend、Keycloak のログイン、注文と決済）を、隔離した compose-test のスタックと Chromium で利用者と同じ経路から操作し、証拠を残す検証スキル。画面、API、注文、決済、ログインに関わる変更を確かめるとき、不具合を再現するとき、Pull Request の Evidence を作るときに使う。
---

# デモアプリケーションの検証

利用者が触るのは `http://localhost:<web のポート>` の SPA である。
web のポートは、`stack.sh up` が取った slot で決まり、`up` の出力に出る（slot 0 は 5173。`docs/tooling/test-slots.md`）。
SPA は Vite preview の proxy を通して backend（`/api/**`、`/oauth2/**`、`/logout`）を同一オリジンで呼び、ログインは Keycloak の画面で行う。
検証は `task e2e` と同じ compose-test の e2e profile を起動したまま残し、Chromium で journey を 1 つずつ実行する。
E2E の規約の正文は `docs/e2e/testing-strategy.md` にあり、この skill は検証の手順だけを持つ。

ヘルパーは `.kiro/skills/verify-demo/scripts/` にある。
コマンドはリポジトリのルートで実行する。

## 前提

- Java（Corretto 25）、Docker、`task`、`vp` が入っている（`docs/local-env-setup/setup.md`）。
  migration とシーダーはホストの Gradle で動くので、Java がないと起動しない。
- `task test` と pre-push のフックは、同じ worktree でも同時に実行してよい。
  別の worktree の `task e2e`、`task scan-dast` とも同時に実行してよい。
  同じ worktree では `task e2e` と `task scan-dast` を同時に実行しない。どちらも `frontend/apps/web/dist` を作り直し、preview が配信中のビルドを壊す。
  実行ごとに空きの slot を取り、slot ごとに Compose project（`spring-modulith-test-<n>`）とポートが分かれる。
  空きがなければ待ち、待っている間は slot を持つ worktree を出力する。
- 別の worktree の検証スタックは操作しない。自分の slot は `build/verify/stack/slot.env` にあり、URL は `up` の出力に出る。

## Launch

```bash
.kiro/skills/verify-demo/scripts/stack.sh up
```

空きの slot を取って `build/verify/stack/slot.env` に残し、起動の手順を `task e2e-up` に任せ、`task e2e` と同じにする（`docs/e2e/testing-strategy.md` の「実行と後片付け」）。
その後、`task be-seed` で開発用の代表データ（テナントと所属、商品、`SEED-C01` から `SEED-C06` の注文）を入れる。
`task e2e` は代表データを使わないが、journey は代表データを読む。
その後 Vite preview を自分の process group で起動し、preview の proxy から `/actuator/health/readiness` が 200 を返すまで待つ。
`検証スタックの準備ができました` が出たら使える。
初回は build で数分かかる。
tool call の時間切れに備え、`mkdir -p build/verify && nohup ... > build/verify/up.log 2>&1 &` で起動してログを待ってよい。
出力をパイプ（`| tail` など）に渡さない。
migration とシーダーが残す Gradle の daemon がパイプを開いたままにし、`up` が終わってもパイプの先が戻らない。

残った状態（`build/verify/stack/`）を見つけたら、理由を出して止まる。
`stack.sh down` を実行してから `up` をやり直す。
`task e2e-up` が失敗したときと、`up` が打ち切られたときも、`stack.sh down` で片付ける。

## Doctor

```bash
.kiro/skills/verify-demo/scripts/stack.sh doctor
```

読み取りだけをする（`build/verify/stack/slot.env` を読む）。
slot の Compose project のコンテナが `up` の記録と同じこと、自分の preview が生きていること、SPA の配信、preview の proxy から backend の readiness、Keycloak の issuer、WireMock の管理 API、代表の注文 6 件を確かめる。
起動した後に `backend`、`frontend`、`docker`、`openapi` のソースが変わっていれば FAIL にする。
古いビルドを検証しないよう、そのときは `down` と `up` をやり直す。
journey の前と、何かがおかしいと感じたときに最初に実行する。

## Drive

```bash
node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-payment.mjs [key=value ...]
```

`drive.mjs` は Chromium を起動し、Keycloak の画面から `.env.test` の `E2E_USERNAME`（`test-user`）でログインしてから journey を呼ぶ。
Playwright と E2E の補助（`frontend/apps/web/e2e/` の `environment.ts`、`payment-gateway.ts`、`api.ts`、`fixtures.ts` の `uniqueCode`、画面のクラス `order-*-page.ts`）は、そのまま import する。
代表データを読む補助は、E2E ではなくこの skill の `scripts/seeded.mjs` にある。
`/collect` は E2E と同じく 202 で返す（compose-test に Collector がない）。

journey は ESM の 1 ファイルで、既定の export の関数が次を受け取る。

- **`page`、`context`**：ログイン済みの Playwright の page と context（`baseURL` は slot の web の origin）
- **`expect`**：`@playwright/test` の `expect`
- **`webOrigin`、`idpOrigin`**：slot の SPA の origin（`http://localhost:<web のポート>`）と Keycloak の origin（`http://127.0.0.1:<Keycloak のポート>`）。journey に 5173 や 8081 を書かない
- **`request`**：`@playwright/test` の `request`（`request.newContext({ baseURL })` で Cookie を共有しない要求を作る）
- **`step(label, page?)`**：その時点の screenshot と ARIA snapshot を `NN-<label>.png`、`NN-<label>.aria.yml` に残す
- **`screensOf(page)`**：その page の画面のクラス `{ create, list, detail }`（`e2e/order-create-page.ts`、`order-list-page.ts`、`order-detail-page.ts`）。別のタブには、そのタブの page を渡して作る
- **`helpers`**：`e2e/api.ts` の `listPayments`、`readJson`、`e2e/fixtures.ts` の `uniqueCode` と、`scripts/seeded.mjs` の export すべて（代表の注文の表、`listProducts`、`onSaleProduct`）
- **`expectPaymentText(page, text)`**：決済の欄の文言が `text` になるのを、画面を読み直さず `OrderDetailPage.paymentTextTimeoutMs` まで待つ
- **`gateway.chargeCount`**：決済代行の WireMock が、注文 ID を冪等性キーにした請求を受けた回数（`e2e/payment-gateway.ts`）
- **`gateway.failChargesFor`**：注文 ID の請求だけを `{ kind: "status" | "delay" | "declined" }` で失敗させ、取り除く関数を返す
- **`sql(query, options?)`**：モジュールのロールで SQL を実行し、結果の文字列を返す（`sql.log` にも残す）
- **`login(page?)`**：Keycloak の画面からログインし、テナント選択でサンプル商事を選んでトップページへ戻る。`export const login = false` の journey は自動のログインを省く
- **`params`、`record(k, v)`**：コマンドラインの `key=value`、`result.json` の `records` に証拠の値を足す

`sql` は次のように使う。

- 接続はモジュールのロール `payment_module` で、`payment` と `ordering` を読める。
  アプリのロール `demo` は業務のスキーマを読めない（ADR-094）。
  `product` は読めない。
- クエリは、ログインの後に選ぶサンプル商事のテナントで流れ、ほかのテナントの行は見えない（ADR-107）。
  `drive.mjs` は `scripts/seeded-tenant.sql` でテナントを名前から引き、そのテナントに代表の注文がなければ journey の前に止まる。
- 値は第 2 引数のオブジェクトで渡し、クエリの中で `:'name'` と書く（例：`sql("select ... where public_id = :'orderId'", { orderId })`）。
  クエリの文字列に値を埋め込まない。
- 複数の列は `|` で区切った 1 つの文字列で返る（例：`PAID|1200|CONFIRMED`）。
- 読み取り専用のトランザクションにするのは誤操作の防止で、クエリの中で解除できる。
  更新の SQL を書かない。

操作の手順と handle は `features/` の各ファイルにあり、各ファイルの手順を実行する journey が `journeys/` にある。
変更に合わせて journey の確かめる内容を足すときは、`features/` の手順も同じ変更で直す。
journey がない機能を足すときは、`features/` に手順を書いてから `journeys/<機能>.mjs` を書く。
要素の探し方と待ち方は `docs/e2e/testing-strategy.md` の「書き方」に従う。

## Evidence

各実行は `build/verify/runs/<UTC時刻>-<journey>/` に次を残す（`build/` は Git から除外）。

- `NN-<label>.png` と `NN-<label>.aria.yml`：各操作の後の画面と構造
- `steps.log`、`network.log`（`/api/**` の method、path、status）、`console.log`、`sql.log`
- `trace.zip`：`cd frontend/apps/web && vp exec playwright show-trace <絶対パス>` で開く。
  Cookie を含むので外へ出さない。
- `result.json`：`passed` か `failed`、例外、`records`。
  失敗時は `failure.png` も残る。

証明の基準は次のとおり。

- 画面の操作で行う。
  `page.request` で API を直接呼ぶのは、前提の読み取り（商品の一覧など）と副作用の確認だけにする。
- 操作の前と後の状態を `step` で残す。
  最後の画面だけを証拠にしない。
- 画面の表示に加えて副作用を確かめる。
  行は `sql`、決済代行への請求は `gateway.chargeCount`、backend のログは片付けで残るファイルで見る。
- 偽物は、E2E と同じく決済代行の WireMock だけにする（`docs/e2e/testing-strategy.md` の「外部システムの偽物」）。
- 失敗した journey を通すために手順を弱めない。
  前提が足りないときは失敗として報告し、理由を該当する `features/` の `Gotchas` に書く。

## Cleanup

```bash
.kiro/skills/verify-demo/scripts/stack.sh down
```

preview のログを `build/verify/logs/` に写し、自分が起動した preview の process group だけを止める。
PID の記録が今の起動のものでなければ、止めない。
slot の Compose project のコンテナが `up` の記録と同じときだけ、`task e2e-collect-logs` で backend のログを `build/verify/logs/` に書いてから `down --volumes --remove-orphans` で消す。
`up` が途中で打ち切られて記録がないときは、`build/verify/stack/` を作った後に作られたコンテナだけを、その起動のものとして消す。
記録と違うコンテナは、ほかの実行が立て直したものとみなして止めず、`build/verify/stack/` だけを消す。
ログに秘密の値かその形の文字列が入っていれば、片付けた後に失敗を返す。
`build/verify/runs/` と `build/verify/logs/` の証拠は消さない。
失敗した試行の後も毎回実行する。

`stack.sh` の片付けを変えたら、検証スタックを止めた状態で次を実行する。
古い PID の記録と、ほかの実行の compose-test を止めないことを確かめる。

```bash
.kiro/skills/verify-demo/scripts/stack-test.sh
```

## 機能の一覧

`features/README.md` を読み、確かめる機能のファイルを開く。
変更が機能の複数の入口に関わるときは、ファイルに載るすべての入口を確かめる。
アプリが変わったら `/maintain-verification-skill` で一覧を直す。
