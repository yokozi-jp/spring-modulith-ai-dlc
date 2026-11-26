---
type: Convention
title: E2E テストの方針と書き方
description: E2E テストの対象、配置、テストデータ、外部システムの偽物、認証、アクセシビリティの検査、書き方、不安定なテスト、実行環境、後片付け、失敗の調べ方、CI を定める規約。E2E テストを追加、変更するとき、Playwright の設定や task e2e、E2E の CI を変えるとき、E2E の失敗を調べるときに読む。
tags: [convention, e2e, playwright, testing, accessibility]
---

# E2E テストの方針と書き方

E2E テストは、複数画面にまたがる主要な利用者の流れだけを Playwright と Chromium で確かめる。
各テストは画面と公開 API で自分専用のデータを作り、共有データを変更せず、実行順に依存しない。
認証は setup project が一度だけ Keycloak の画面で行い、`storageState` をコミットしない。
変更後は `task e2e` で確かめる。
道具と環境を選んだ理由は [ADR-057](../adr/ADR-057-adopt-playwright-for-e2e-tests.md) にある。

## 対象

- 複数画面にまたがる主要な利用者の流れに限る。
- unit test と component test で確かめられる観点は E2E に書かない。
  観点の割り当ては[テスト観点の割り当て](../frontend/test-strategy.md)に従う。
- 例外として、画面ごとのアクセシビリティの axe の検査を E2E に置く。jsdom は描画の色を計算できないためである（[アクセシビリティの検査](#アクセシビリティの検査)）。

## 配置と構成

`frontend/apps/web/e2e/` に、ログインや WireMock の初期化を行う `*.setup.ts` とシナリオの `*.spec.ts` を置く。
`frontend/apps/web/e2e/environment.ts` は、ログイン情報をルートの `.env.test` から読み、`storageState` のパスを決める。
web の origin（`webOrigin`）と IdP の origin（`idpOrigin`）は、slot が渡す `E2E_WEB_PORT` と `OIDC_ISSUER_URI` から決め、無ければ slot 0 の値を使う。
Keycloak の画面でログインする手順（`signInOnKeycloak`）も持ち、setup、ログアウトの spec、未認証の spec が共有する。
`frontend/apps/web/e2e/pages.ts` は、ログアウトのボタンと、注文の画面以外で 2 つ以上の spec が待つ見出しの locator、ログアウト後の画面と認証済みのトップページの確認、未認証で開いたときのログインの入力画面の確認を持つ。
`telemetry.ts` と `pages.ts` は Playwright の中だけで使うため、相対の import に拡張子を付けない。
`frontend/apps/web/e2e/payment-gateway.ts` は、決済代行の WireMock の管理 API を呼ぶ補助を持つ。
注文ごとの失敗のスタブ（`failChargesFor`）と、注文 ID を冪等性キーにした請求の回数（`chargeRequestCount`）である。
`frontend/apps/web/e2e/fixtures.ts` は、商品を登録する API（`POST /api/v1/products`）で商品を作る `productFactory`、画面で注文を作る `createOrder` と、axe で画面を検査する `makeAxeBuilder` と `expectNoA11yViolations` を持つ（[アクセシビリティの検査](#アクセシビリティの検査)）。
`frontend/apps/web/e2e/api.ts` は、公開 API の応答の本文を読む `readJson` と、注文の決済記録を読む `listPayments` を持つ。
`environment.ts`、`fixtures.ts`、`api.ts`、`payment-gateway.ts` と、画面のクラス（`order-create-page.ts`、`order-list-page.ts`、`order-detail-page.ts`）は、Playwright の外から Node が直接 import できるよう、相対の import に拡張子 `.ts` を付ける。
`task fe-check` が、この 7 つを Node で import できることを確かめる。
`fixtures.ts` が import する `@axe-core/playwright` も、この import で解決できることを確かめる。
画面の操作と文言は、[ページオブジェクト](#ページオブジェクト)に従い、画面ごとのクラスに置く。
決済の欄は、確定した注文に決済記録がない間、確定から 15 秒まで 1 秒ごとに自分で読み直し、その間は処理中を出す。
期間が過ぎても記録がなければ再投入待ちを出し、読み直しのボタンを出す。
決済の欄の文言を待つ spec は、画面を読み直さず、`OrderDetailPage` の `paymentStatus` が変わるのを `paymentTextTimeoutMs`（30 秒）まで待つ。
再投入しないことの確認は、画面を読み直さずに読み直しのボタンを押す。

業務の流れの spec は次の 2 つである。

- `order-payment.spec.ts`：注文の作成、確定、Listener の請求、決済の表示と、決済代行の 5xx、タイムアウト、拒否、CSRF を確かめる。
  一覧の状態の絞り込み、取消済みの注文の詳細、作成の画面で販売終了の商品を出さないことも、自分で作った注文と商品で確かめる。
- `order-conflict.spec.ts`：同じ注文を 2 つのタブで開き、楽観的ロックの競合と、画面が示す回復の選択肢ごとの結果を確かめる。

画面ごとのアクセシビリティは `a11y.spec.ts` が確かめる（[アクセシビリティの検査](#アクセシビリティの検査)）。

`frontend/apps/web/playwright.config.ts` の設定は次のとおりである。

| 項目              | 値                                                                                  |
| ----------------- | ----------------------------------------------------------------------------------- |
| project           | `setup`（`*.setup.ts`）と、`setup` に依存する `chromium`（`*.spec.ts`）             |
| locale            | `ja-JP`                                                                             |
| retry             | ローカル 0 回、CI 2 回                                                              |
| trace、screenshot | 失敗時だけ保存する                                                                  |
| video             | 保存しない                                                                          |
| `webServer`       | `vp preview --mode test` を `webOrigin` で起動し、既存の server を再利用しない      |
| `fullyParallel`   | 有効（実行順への依存を早く見つけるため）                                            |
| workers           | 指定しない（Playwright の既定値）                                                   |

## テストデータ

E2E は開発用のシーダー（`LocalDataSeeder`）の代表データを使わない（[ADR-130](../adr/ADR-130-create-e2e-data-per-test-with-product-registration-api.md)）。
各テストは、自分専用のデータを画面か公開 API で作る。

- 注文は、画面か公開 API で作る。画面で作り、下書き、確定済み、取消済みを選ぶときは `createOrder` を使う。決済代行の拒否、5xx、遅延は `failChargesFor` で作る（[外部システムの偽物](#外部システムの偽物)）。
- 商品は、`fixtures.ts` の `productFactory` で作る。
  `productFactory({ onSale: false })` は販売終了の商品を作り、`unitPrice` で単価を決める。
  商品コードと商品名は呼ぶたびに一意になる。
- `productFactory` は、テストの page（共有の `storageState` でログインし、テナントを選んだ状態）の Cookie で、商品を登録する API（`POST /api/v1/products`）を呼ぶ。
  CSRF の token（`__Host-XSRF-TOKEN` の値を `X-XSRF-TOKEN` に付ける）と `Idempotency-Key` は `productFactory` の中でだけ付ける。
  `page.request` は CSRF の token を自分では付けないので、spec が `page.request` で直接送る更新の要求は 403 になる（`order-payment.spec.ts` が確かめる）。
- 他のテストと衝突しないよう、客先注文番号には `fixtures.ts` の `uniqueCode` で一意な値を含める。
  一覧には並列のテストのデータも並ぶので、自分の商品名と客先注文番号で絞って数える。
- 共有するのは、テナントと所属（`task e2e-up` が `task be-seed-tenancy` で入れる）と、Keycloak の利用者（`docker/keycloak/realm.json`）だけである。
  共有のデータは読むだけにし、変更しない。
- テストの実行順や、他のテストが作ったデータに依存しない。
- spec に `SEED-` の文字列を書かない。`task fe-check` の `lint-e2e-seed` が失敗にする。
- 画面と公開 API で作れない状態が必要になっても、テストのためだけの入口（Profile やプロパティで有効にする入口、テストのための別プロセス）を作らない。
  業務の操作として API が要るなら、その API を本番に足す。

## 外部システムの偽物

外部システムは、compose-test の WireMock が偽る（[ADR-072](../adr/ADR-072-fake-external-systems-with-wiremock.md)）。
backend を再起動せず、シナリオの途中で注文ごとに応答を切り替える。

- 成功の応答は、`docker/wiremock/mappings/` の共有のスタブが返す。
- 失敗（5xx、遅延）と業務上の拒否（`201` の `status: DECLINED`）は、`failChargesFor` で管理 API（`POST /__admin/mappings`）から足し、テストの終わりに返された関数で取り除く（`DELETE /__admin/mappings/{id}`）。
  スタブは冪等性キー（注文 ID）で絞り、共有のスタブより優先度を高くするため、並列のテストと他の注文は成功のままになる。
- setup project の `payment-gateway.setup.ts` が、開始時に `POST /__admin/mappings/reset` を一度呼ぶ。
  前の実行で後片付けが走らずに残った注文ごとのスタブを持ち越さないためである。
- 全体に効く失敗のスタブを足さない。
  並列のテストと他の注文まで失敗させる。

## 送ったメールを読む

backend が送ったメールは、compose-test の WireMock（`.env.test` の `PAYMENT_GATEWAY_BASE_URL`。決済代行と同じ WireMock を指す）の要求の記録から読む（[ADR-125](../adr/ADR-125-send-mail-with-ses-v2-api-and-fake-it-with-wiremock.md)）。
メールを読む spec はまだない。

- 管理 API の `POST /__admin/requests/find` に、`/v2/email/outbound-emails` のパスと、`$.Destination.ToAddresses[0]` で絞った一意の宛先を送ると、`requests[].body` に SDK が送った JSON の文字列が返る。

## 認証

setup project は、`storageState` を持たない browser context で `/oauth2/authorization/web` を開き、Keycloak の画面で `test-user` としてログインする。
`/` から始めると、SPA が API の 401 を受けて自分でログインへ遷移し、setup project の遷移と競合する。
この遷移そのものは、ログアウト後に `/` を開く `logout.spec.ts` のテストと、未認証で認証必須の画面を開く `unauthenticated-redirect.spec.ts` が確かめる。
`test-user` は `task e2e-up` が入れる所属で 2 つのテナントに属するため、戻り先はテナントを選ぶ画面（web の origin の `/tenants`）になる。
認証の前後で `__Host-APP_SESSION` Cookie の値が変わったことで認証済みと判断し、サンプル商事を選ぶ。
選んだ後に web の origin の `/` へ移り、`__Host-APP_SESSION` の値がもう一度変わったことを確かめてから、`frontend/apps/web/e2e/.auth/user.json` に保存する。
`__Host-APP_SESSION` は認証の開始時点で発行されるため、Cookie があることだけを認証の証拠にしない。
自分でログインする spec も、ログインの後に `environment.ts` の `chooseSeededTenant` でテナントを選ぶ。

- spec は保存した `storageState` を共有し、ログインを繰り返さない。
- 共有の `storageState` を使うテストはログアウトしない。
  ログアウトを確かめるテストは、専用の browser context でログインする。
- `frontend/apps/web/e2e/.auth/`、`frontend/apps/web/playwright-report/`、`frontend/apps/web/test-results/` はコミットしない（`frontend/.gitignore` が除外する）。
- `storageState` のファイルは CI の artifact に入らない。
  ただし中身の Cookie は失敗時の trace に入る。
  その Cookie は後片付けで破棄した環境のものである。

ログインの失敗は、認可リクエストのないコールバックを直接開く `login-failed.spec.ts` が、配信点の proxy を通って `/login-failed` の画面に着くことで確かめる。

操作の途中のセッション切れは、専用の browser context でログインして `/orders` を開き、`__Host-APP_SESSION` の Cookie を外してから一覧の絞り込みを操作する `session-expired.spec.ts` が確かめる。
API が成功した後の 401 なので、`/session-expired` に着き、「もう一度ログイン」から絞り込んだ画面へ戻る。
Cookie を外した要求は、DB のセッションを消した要求と同じく、サーバー側で未認証になり 401 を受ける。

## 書き方

- 要素は role と accessible name で探す。
  role で取れないもの（`html` の `lang` など）だけ locator を使う。
- 待ちは web-first assertion（`await expect(locator).toBeVisible()` など）に任せ、固定時間で待たない。
- `test.only` は CI の `forbidOnly` が失敗させる。
- Lint はルートの `frontend/vite.config.ts` の設定で `vp check` が検査する。
  Playwright Test の API に当たる vitest plugin の規則だけを `apps/web/e2e/**` で外している（[フロントエンドのLint設定](../frontend/lint-config.md#ファイルの範囲ごとの制限)）。
  `apps/web/e2e/**` からもテレメトリの SDK（`@grafana/*`）を import できない。

## アクセシビリティの検査

`a11y.spec.ts` は、画面ごとに axe（`@axe-core/playwright`）を流し、違反が 1 件でもあれば失敗にする（[ADR-142](../adr/ADR-142-check-accessibility-with-axe-in-playwright-e2e.md)）。

- 規則は、タグ `wcag2a`、`wcag2aa`、`wcag21a`、`wcag21aa`、`wcag22aa`、`best-practice` で選ぶ。`fixtures.ts` の `makeAxeBuilder` がこのタグの `AxeBuilder` を返す。
- テストは、`a11y.spec.ts` の画面の表 `screens` と配色の表 `variants`（Playwright の `colorScheme`）の組ごとにできる。
  画面を足すときは、`screens` に、`app` の fixture を受けて画面を開き見出しを待つ `open` を 1 行足す。配色や幅の組を足すときは、`variants` に 1 行足す。
- Keycloak のログインの画面は、この repo の画面ではないので含めない。
- 流れの中でしか届かない状態は、その流れの spec が `expectNoA11yViolations` を呼ぶ。[#647](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/647) が既存の spec の中での検査を求めたので、axe の違反はその流れのテストを失敗にする。
- `expectNoA11yViolations` は、マウスを左上へ動かしてから流し、screenshot と `incomplete` を含む結果の JSON を HTML report に添付する。違反は、規則の id、対象の selector、`helpUrl` を失敗の文言に出す。
- `incomplete`（axe が自動で決められなかった項目）は失敗にしない。report の JSON を読んで、人が確かめる。

## ページオブジェクト

画面の操作と要素の探し方は、画面ごとのクラス（ページオブジェクト）に置く。
spec は `new` せず、`fixtures.ts` の `test.extend` が注入する fixture（`orderCreatePage`、`orderListPage`、`orderDetailPage`）で受け取る。

- クラスは、画面の操作のメソッドと、要素の Locator と、画面の定数を持つ。
  固定の Locator は getter、引数を取る Locator は引数付きのメソッドにする。
  要素を探すための文言は Locator の中に置く。
  spec が期待値として直接使う文言（`paymentNotYetText`）と、画面が自分で状態を変えるのを待つ上限（`paymentTextTimeoutMs`）は、定数としてクラスに置く。
  1 つの spec だけが使う期待値の文言は、その spec に置いてよい。
  Locator は、CSS や test id を使わず、利用者から見える名前（role と accessible name、label、画面の文言）で探す。
- アサーションは spec に置く。
  クラスは「何を操作するか」を持ち、「何を確かめるか」は持たない。
  `expect` を呼ばないので、クラスは `fixtures.ts` を import しない。
- 別のタブで同じ画面を開くときは、クラスのメソッド（`OrderDetailPage.openInNewTab`）が同じクラスの新しいインスタンスを返す。
  spec が `new` で作らない。
- クラスは `Page` だけを受け取り、`Page` を公開しない。
  `page.request` や screenshot が要る spec は、`page` fixture を別に受け取る。
- 複数の画面が使う部品（明細の表、決済の欄など）は、画面のクラスが共有するなら別のクラス（Component Object）に分ける。
  今は、同じ部品を使う画面が 1 つなので分けていない。
- 状態の名前（`OrderStatus`、`OrderStatusLabel`）は、どの画面のクラスにも属さないので `order-status.ts` に置く。
- 注文の画面以外の spec（ログイン、ログアウト、トップページ）は、`pages.ts` の関数のままである。
  画面のクラスを足す spec は `<画面>-page.ts` に置き、`pages.ts` には足さない。
  `pages.ts` の関数は、その spec を変更するときに画面のクラスへ移す。
- Screenplay は導入しない。画面のクラスで足りる規模である。

## 不安定なテスト

CI の retry は、不安定なテストを見つけるためにある。
retry で成功したテストは report に flaky として残るため、同じ変更か直後の変更で原因を直すか、テストを削除する。
retry の回数を増やして隠さない。

## 実行環境

`task e2e` は `docker/compose-test.yml` の `e2e` profile で PostgreSQL、Keycloak、決済代行の WireMock、backend を起動する。
WireMock には profile を付けず、`task test` と共有する。
backend は compose の network に置き、PostgreSQL、Keycloak、WireMock へ service 名で接続する。
compose の `environment` が `.env.test` の接続先を service 名に上書きする。
Keycloak は hostname v2 で issuer をブラウザと同じ `http://127.0.0.1:<slot の Keycloak の port>` に固定し、backend は discovery を `OIDC_DISCOVERY_URI`（`keycloak:8080`）から読む。
backend は discovery の `issuer` が `OIDC_ISSUER_URI` と一致しなければ起動しない（理由は [ADR-057](../adr/ADR-057-adopt-playwright-for-e2e-tests.md)）。
compose が公開する port は、すべて `127.0.0.1` に限る。

`task e2e` は実行ごとに slot を取り、slot の Compose project（`spring-modulith-test-<n>`）と port で起動する。
複数の worktree で同時に実行してよく、`task test` とも同時に実行できる。
slot の割り当て、slot 1 から 3 の port、空きがないときの待ちは、[compose-test の slot](../tooling/test-slots.md) にある。
slot 0 の port は次のとおりである。

| port | 用途                                                       |
| ---- | ---------------------------------------------------------- |
| 5173 | Vite preview（Keycloak の redirect URI と同じ origin）     |
| 8080 | backend                                                    |
| 5433 | PostgreSQL                                                 |
| 8081 | Keycloak                                                   |
| 8082 | 決済代行と SES の WireMock（Playwright が管理 API を呼ぶ） |

- slot 0 の 5173 と 8080 は、開発用の Vite と Keycloak と同じ port である。
  slot を取るときに、port が `127.0.0.1` で応答する slot は飛ばす。
  slot を取った後も、`task e2e-up` が web と backend の port を確かめ、使用中なら止めるよう示して失敗する。
- 前提の道具は、[開発環境構築](../local-env-setup/setup.md)のブロック2（`mise install --locked` と `task setup`）で入れたものと同じである。
  Chromium は `task e2e` が導入する。
  OS の依存ライブラリは、初回に `frontend/apps/web` で、`sudo` を付けた `vp exec playwright install-deps chromium` を実行して入れる。

## 実行と後片付け

`task e2e` は次の順に実行する。

1. slot を取り、slot の番号、web、backend、Keycloak、WireMock の URL、Compose project を出す。
2. `task e2e-up` を実行する。
   slot の web と backend の port（slot 0 は 5173 と 8080）が空いていることを確かめ、frontend の依存の導入と build、Chromium の導入、backend イメージの build を行う。
   frontend は `vp build --mode test` で build し、`.env.test` の `FRONTEND_OTEL_ENABLED=true` で Faro を有効にする。
3. PostgreSQL、Keycloak、WireMock を起動し、`task be-migrate` で migration を適用する。
   `task be-seed-tenancy`（[DB 操作コマンドのリファレンス](../database/commands.md)）でテナントと所属だけを入れる。
4. backend を起動し、ホストから readiness を確かめる。
5. Playwright を実行する。
   Playwright が `webServer` で Vite preview を起動し、終了時に止める。
6. 成否にかかわらず、`task e2e-collect-logs` で backend のログを `frontend/apps/web/test-results/backend.log` に書き、slot の project のコンテナと volume を削除して slot を返す。
   ログに `.env.test` の `OIDC_CLIENT_SECRET` か `E2E_PASSWORD` の値が入っていれば、テストが成功していても失敗にする。
   値の一致で見つからない秘密として、`__Host-APP_SESSION=` に続く Cookie の値と、`eyJ` で始まる 3 つの base64url をつないだ JWT の形の文字列も失敗にする。

手順 2 から 4 の `task e2e-up` は片付けを行わない。
`task e2e` の外で起動して残す呼び出し側は、片付けの前に `task e2e-collect-logs LOG=<パス>` で同じ検査をし、自分で片付ける。

E2E には Collector を置かず、テストが `/collect` を `page.route` で応答する（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
`frontend/apps/web/e2e/logout.spec.ts` は、`/collect` が 503 を返してもログインとログアウトのフォームを送信できることを確かめる。

起動済みの環境に対して Playwright だけを実行するときは、`frontend/` で `vp run --filter @frontend/web e2e` を実行する。
build、コンテナの起動、後片付けは行わない。

- ログイン情報は、ルートの `.env.test` の `E2E_USERNAME` と `E2E_PASSWORD` から読む。
  試しに値を変えるときは、Git から除外されたルートの `.env.test.local` に書く。
- `.env.test` は、Git で管理する `.env.test.example` からコピーして作る Git 除外のファイルである。
  `task e2e` と `task fe-knip`（Knip が Playwright の設定を読む）は、`.env.test` が無ければ `.env.test.example` からコピーする。
- `task e2e` は `TEST_ENV_FILE` の上書きに対応せず、`.env.test` を固定で読む。
  compose の backend と Playwright が `.env.test` を固定で読むためである。
- ローカルで `E2E_KEEP_ENV=1 task e2e` を実行して失敗したときだけ、コンテナと volume を残す。
  残したときは、slot の番号と Compose project、backend のログ、`vp run --filter @frontend/web e2e` での再実行、片付けのコマンドを表示する。
  残した環境は、表示した `down` のコマンドで片付けるまで slot を持つ。
- `CI` が空でなければ、`E2E_KEEP_ENV` にかかわらず片付ける。
- Ctrl-C で中断すると、後片付けが走らず、コンテナが slot を持ったまま残ることがある。
  次の `task e2e` は残った環境を消さず、別の slot を取る。
  片付け方は [compose-test の slot](../tooling/test-slots.md#中断した実行が残した-slot) にある。

## 失敗の調べ方

- `frontend/apps/web/playwright-report/` の HTML report を開く。
- `frontend/apps/web/test-results/` の trace を、`frontend/apps/web` で `vp exec playwright show-trace <trace.zip>` を実行して開く。
- `task e2e` は成否にかかわらず backend のログを `frontend/apps/web/test-results/backend.log` に書く。
  `test-results/` は Playwright の出力先のため、`vp run --filter @frontend/web e2e` で再実行すると `backend.log` も消える。
  残した環境では、`task e2e` が表示する `docker compose -p spring-modulith-test-<n> -f docker/compose-test.yml --profile e2e logs backend` で読む。
- CI では、失敗時に `e2e-results` artifact に report、trace、`backend.log` を保存する。

## 証拠

- spec は、主要な状態（決済済み、再投入待ち、拒否、対象外、競合の選択肢）の screenshot を、`expectNoA11yViolations` で axe の結果と一緒に HTML report に添付する。
- CI は、成功時に `e2e-evidence` artifact に HTML report を保存する。
  `backend.log` は失敗の調査にだけ使うため、成功時には保存せず、失敗時の `e2e-results` だけに入れる。
  retry で成功したテストも、失敗した試行の trace を HTML report に残す。
  成功時の artifact は `playwright-report/data/*.zip` を除くため、trace と、trace に入る Cookie を含まない。
  失敗した試行の screenshot は、成功時の artifact にも残る。
- `backend.log` に秘密の値、セッションの Cookie の値、JWT の形の文字列が入っていないことは、`task e2e` の後片付けが検査する（[実行と後片付け](#実行と後片付け)）。

## E2E で確かめない範囲

- イベント出版の状態、試行の回数、再投入の後の決済記録は、backend の `PaymentGatewayClientIntegrationTest` が本番の Client と手での 1 回の再投入で確かめる。
  出版の状態を読む公開の入口も、再投入の公開の入口も無く、共有の backend の DB に手での 1 回の再投入を起動すると並列のテストの出版も再投入して壊すためである。
  E2E の backend は `.env.test` で定期の再投入を無効にしている（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
  E2E は、一時障害のあいだと成功に戻した後に、画面が再投入待ちを表示し、請求が 1 回のままであることだけを確かめる。
- Safari と WebKit では実行しない。
  CSRF の Cookie は `Secure` の `__Host-` の名前で（[ADR-066](../adr/ADR-066-harden-csrf-cookie-with-host-prefix.md)）、Safari が `http://localhost` でこの Cookie を保存するかは未確認である（[#158](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/158)）。
  保存しなければ更新の要求に token を付けられないため、WebKit では CSRF で守る更新、ログアウト、注文の流れの全体が未検証である。
- Tempo と Loki での trace とログのつながり（backend、ordering、Listener、payment）は、`task e2e` では確かめない。
  E2E には Collector を置かない（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
  代わりに、backend の `OrderConfirmedListenerTest` と `CommonColumnsListenerTraceTest` が確定の trace の Listener への引き継ぎを確かめる。

## CI

`.github/workflows/e2e.yml` は、Pull Request で backend（DB の changeset を含む）、frontend、Keycloak の設定、compose-test とその入力（`docker/initdb/`、`docker/wiremock/`、`.env.test.example`）、Taskfile、この workflow 自体を変えたときだけ `task e2e` を実行する。
retry は 2 回で、失敗時に Playwright の成果物と backend のログを、成功時に HTML report だけを保存する。
CI の `task e2e` は slot 0 を使う。
`task e2e` の後に、`task test-deps-leftover-check` で名前に `spring-modulith-test` を含むコンテナと volume を調べ、残っていれば失敗にする。
Backend CI も、compose-test を止めた後に同じ task で確かめる。
この check は required status checks に登録しない（[ブランチ保護](../repository/branch-protection.md)）。
