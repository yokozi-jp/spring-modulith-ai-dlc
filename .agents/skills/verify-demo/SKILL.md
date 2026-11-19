---
name: verify-demo
description: デモアプリケーション（React の SPA と Spring Boot の backend、Keycloak のログイン、注文と決済）を、隔離した compose-test のスタックと Chromium で利用者と同じ経路から操作し、証拠を残す検証スキル。画面、API、注文、決済、ログインに関わる変更を確かめるとき、不具合を再現するとき、Pull Request の Evidence を作るときに使う。
---

# デモアプリケーションの検証

利用者が触るのは `http://localhost:5173` の SPA である。
SPA は Vite preview の proxy を通して backend（`/api/**`、`/oauth2/**`、`/logout`）を同一オリジンで呼び、ログインは Keycloak の画面で行う。
検証は `task e2e` と同じ compose-test の e2e profile を起動したまま残し、Chromium で journey を 1 つずつ実行する。
E2E の規約の正文は `docs/e2e/testing-strategy.md` にあり、この skill は手順だけを持つ。

ヘルパーは `.kiro/skills/verify-demo/scripts/` にある。
コマンドはリポジトリのルートで実行する。

## 前提

- Java（Corretto 25）、Docker、`task`、`vp` が入っている（`docs/local-env-setup/setup.md`）。
  migration とシーダーはホストの Gradle で動くので、Java がないと起動しない。
- 開発用の Vite（`task fe-dev`、5173）と開発用の Keycloak（`task compose-up`、8080）を止めてある。
- `task test`、`task e2e`、`task verify`、pre-push のフックを同時に実行しない。
  どれも Compose project `spring-modulith-test` と同じ port を使う。
  並べて 2 つの検証スタックを起動できないので、別の実行のスタックを操作しない。

## Launch

```bash
.kiro/skills/verify-demo/scripts/stack.sh up
```

`task e2e` の手順 2 から 4 と同じことをする。
frontend の `vp build --mode test`、Chromium の導入、backend のイメージの build、PostgreSQL、Redis、Keycloak、WireMock の起動、`task be-migrate` と `task be-seed`（`.env.test`）、backend の起動である。
その後 Vite preview を自分の process group で起動し、preview の proxy から `/actuator/health/readiness` が 200 を返すまで待つ。
`検証スタックの準備ができました` が出たら使える。
初回は build で数分かかる。
tool call の時間切れに備え、`nohup ... > /tmp/verify-up.log 2>&1 &` で起動してログを待ってよい。

起動の前に、残った状態（`build/verify/stack/`）、動いている `spring-modulith-test`、使用中の 5173 と 8080 を見つけたら、理由を出して止まる。
その場合は自分が残したものなら `stack.sh down` を実行し、ほかの実行のものなら終わるまで待つ。

## Doctor

```bash
.kiro/skills/verify-demo/scripts/stack.sh doctor
```

読み取りだけをする。
自分の preview が生きていること、SPA の配信、preview の proxy から backend の readiness、Keycloak の issuer、WireMock の管理 API、代表の注文 6 件を確かめる。
起動した後に `backend`、`frontend`、`docker`、`openapi` のソースが変わっていれば FAIL にする。
古いビルドを検証しないよう、そのときは `down` と `up` をやり直す。
journey の前と、何かがおかしいと感じたときに最初に実行する。

## Drive

```bash
node .kiro/skills/verify-demo/scripts/drive.mjs .kiro/skills/verify-demo/journeys/order-payment.mjs [key=value ...]
```

`drive.mjs` は Chromium を起動し、Keycloak の画面から `.env.test` の `E2E_USERNAME`（`test-user`）でログインしてから journey を呼ぶ。
Playwright は `frontend/apps/web` の devDependencies から読むので、追加の導入は要らない。
`/collect` は E2E と同じく 202 で返す（compose-test に Collector がない）。

journey は ESM の 1 ファイルで、既定の export の関数が次を受け取る。

| 名前                    | 内容                                                                                                      |
| ----------------------- | --------------------------------------------------------------------------------------------------------- |
| `page`、`context`       | ログイン済みの Playwright の page と context（`baseURL` は `http://localhost:5173`）                      |
| `expect`                | `@playwright/test` の `expect`                                                                            |
| `step(label, page?)`    | その時点の screenshot と ARIA snapshot を `NN-<label>.png`、`NN-<label>.aria.yml` に残す                  |
| `sql(query)`            | アプリのロールで読み取り専用の SQL を実行し、結果の文字列を返す（`sql.log` にも残す）                     |
| `gateway.chargeCount`   | 決済代行の WireMock が、注文 ID を冪等性キーにした請求を受けた回数                                        |
| `gateway.failChargesFor` | 注文 ID の請求だけに WireMock の応答（`{ status: 503 }` など）を足し、取り除く関数を返す                 |
| `login(page?)`          | Keycloak の画面からログインする。`export const login = false` の journey は自動のログインを省く            |
| `params`、`record(k,v)` | コマンドラインの `key=value`、`result.json` に証拠の値を足す                                              |

操作の手順と handle は `features/` の各ファイルにある。
既存の journey がない機能は、`features/` の手順で `journeys/<機能>.mjs` を書いてから実行する。
要素は role と accessible name で探し、待ちは web-first assertion に任せる（`docs/e2e/testing-strategy.md` の「書き方」）。

## Evidence

各実行は `build/verify/runs/<UTC時刻>-<journey>/` に次を残す（`build/` は Git から除外）。

- `NN-<label>.png` と `NN-<label>.aria.yml`：各操作の後の画面と構造
- `steps.log`、`network.log`（`/api/**` の method、path、status）、`console.log`、`sql.log`
- `trace.zip`：`cd frontend/apps/web && vp exec playwright show-trace <絶対パス>` で開く。Cookie を含むので外へ出さない
- `result.json`：`passed` か `failed`、例外、`record` で足した値。失敗時は `failure.png` も残る

証明の基準は次のとおり。

- 画面の操作で行う。`page.request` で API を直接呼ぶのは、前提の読み取り（商品の一覧など）と副作用の確認だけにする。
- 操作の前と後の状態を `step` で残す。最後の画面だけを証拠にしない。
- 画面の表示に加えて副作用を確かめる。行は `sql`、決済代行への請求は `gateway.chargeCount`、backend のログは片付けで残るファイルで見る。
- 偽物は決済代行の WireMock だけにする。本番でも外部システムとの境界なので、ほかを偽らない。
- 失敗した journey を通すために手順を弱めない。前提が足りないときは失敗として報告し、理由を該当する `features/` の `Gotchas` に書く。

## Cleanup

```bash
.kiro/skills/verify-demo/scripts/stack.sh down
```

backend と preview のログを `build/verify/logs/` に書いてから、自分が起動した preview の process group を止め、compose-test を `down --volumes --remove-orphans` で消す。
`build/verify/stack/` がなければ何も止めない（ほかの実行の compose-test を止めないため）。
`build/verify/runs/` と `build/verify/logs/` の証拠は消さない。
失敗した試行の後も毎回実行する。

## 機能の一覧

`features/README.md` を読み、確かめる機能のファイルを開く。
変更が機能の複数の入口に関わるときは、ファイルに載るすべての入口を確かめる。
アプリが変わったら `/maintain-verification-skill` で一覧を直す。
