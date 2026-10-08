---
type: Convention
title: E2E テストの方針と書き方
description: E2E テストの対象、配置、テストデータ、外部システムの偽物、認証、書き方、不安定なテスト、実行環境、後片付け、失敗の調べ方、CI を定める規約。E2E テストを追加、変更するとき、Playwright の設定や task e2e、E2E の CI を変えるとき、E2E の失敗を調べるときに読む。
tags: [convention, e2e, playwright, testing]
---

# E2E テストの方針と書き方

E2E テストは、複数画面にまたがる主要な利用者の流れだけを Playwright と Chromium で確かめる。
各テストは公開 API で自分専用のデータを作り、共有データを変更せず、実行順に依存しない。
認証は setup project が一度だけ Keycloak の画面で行い、`storageState` をコミットしない。
変更後は `task e2e` で確かめる。
道具と環境を選んだ理由は [ADR-057](../adr/ADR-057-adopt-playwright-for-e2e-tests.md) にある。

## 対象

- 複数画面にまたがる主要な利用者の流れに限る。
- unit test と component test で確かめられる観点は E2E に書かない。
  観点の割り当ては[テスト観点の割り当て](../frontend/test-strategy.md)に従う。

## 配置と構成

`frontend/e2e/` に、ログインや WireMock の初期化を行う `*.setup.ts` とシナリオの `*.spec.ts` を置く。
`frontend/e2e/environment.ts` は、ログイン情報をルートの `.env.test` から読み、`storageState` のパスを決める。
Keycloak の画面でログインする手順（`signInOnKeycloak`）も持ち、setup とログアウトの spec が共有する。
`frontend/e2e/payment-gateway.ts` は、決済代行の WireMock の管理 API を呼ぶ補助を持つ。
`frontend/playwright.config.ts` の設定は次のとおりである。

| 項目              | 値                                                                                  |
| ----------------- | ----------------------------------------------------------------------------------- |
| project           | `setup`（`*.setup.ts`）と、`setup` に依存する `chromium`（`*.spec.ts`）             |
| locale            | `ja-JP`                                                                             |
| retry             | ローカル 0 回、CI 2 回                                                              |
| trace、screenshot | 失敗時だけ保存する                                                                  |
| video             | 保存しない                                                                          |
| `webServer`       | `vp preview --mode test` を `localhost:5173` で起動し、既存の server を再利用しない |
| `fullyParallel`   | 有効（実行順への依存を早く見つけるため）                                            |
| workers           | 指定しない（Playwright の既定値）                                                   |

## テストデータ

- 各テストは、必要なデータを公開 API で作る。
  他のテストと衝突しないよう、名前や識別子に一意な値を含める。
- 共有のデータ（`test-user` など）は読むだけにし、変更しない。
- テストの実行順や、他のテストが作ったデータに依存しない。
- 公開 API で作れない状態が必要になったら、DB fixture を使う前に ADR で判断する。
- Datafaker のシーダー（ローカル開発用）と E2E のデータを共有しない。

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

## 認証

setup project は、`storageState` を持たない browser context で `/` を開き、`/oauth2/authorization/web` から Keycloak の画面で `test-user` としてログインする。
戻り先が `http://localhost:5173/` であることと、認証の前後で `APP_SESSION` Cookie の値が変わったことで認証済みと判断し、`frontend/e2e/.auth/user.json` に保存する。
`APP_SESSION` は認証の開始時点で発行されるため、Cookie があることだけを認証の証拠にしない。

- spec は保存した `storageState` を共有し、ログインを繰り返さない。
- 共有の `storageState` を使うテストはログアウトしない。
  ログアウトを確かめるテストは、専用の browser context でログインする。
- `frontend/e2e/.auth/`、`frontend/playwright-report/`、`frontend/test-results/` はコミットしない（`frontend/.gitignore` が除外する）。
- `storageState` のファイルは CI の artifact に入らない。
  ただし中身の Cookie は失敗時の trace に入る。
  その Cookie は後片付けで破棄した環境のものである。

## 書き方

- 要素は role と accessible name で探す。
  role で取れないもの（`html` の `lang` など）だけ locator を使う。
- 待ちは web-first assertion（`await expect(locator).toBeVisible()` など）に任せ、固定時間で待たない。
- `test.only` は CI の `forbidOnly` が失敗させる。
- Lint は `vite.config.ts` の設定で `vp check` が検査する。
  Playwright Test の API に当たる vitest plugin の規則だけを `e2e/**` で外している（[Lintとテストのリファレンス](../tooling/lint-and-test.md)）。
  `e2e/**` からもテレメトリの SDK（`@grafana/*`）を import できない。

## 不安定なテスト

CI の retry は、不安定なテストを見つけるためにある。
retry で成功したテストは report に flaky として残るため、同じ変更か直後の変更で原因を直すか、テストを削除する。
retry の回数を増やして隠さない。

## 実行環境

`task e2e` は `docker/compose-test.yml` の `e2e` profile で PostgreSQL、Redis、Keycloak、決済代行の WireMock、backend を起動する。
WireMock には profile を付けず、`task test` と共有する。
backend は compose の network に置き、PostgreSQL、Redis、Keycloak、WireMock へ service 名で接続する。
compose の `environment` が `.env.test` の接続先を service 名に上書きする。
Keycloak は hostname v2 で issuer をブラウザと同じ `http://127.0.0.1:8081` に固定し、backend は discovery を `OIDC_DISCOVERY_URI`（`keycloak:8080`）から読む。
backend は discovery の `issuer` が `OIDC_ISSUER_URI` と一致しなければ起動しない（理由は [ADR-057](../adr/ADR-057-adopt-playwright-for-e2e-tests.md)）。
compose が公開する port は、すべて `127.0.0.1` に限る。

| port | 用途                                                   |
| ---- | ------------------------------------------------------ |
| 5173 | Vite preview（Keycloak の redirect URI と同じ origin） |
| 8080 | backend                                                |
| 5433 | PostgreSQL                                             |
| 6380 | Redis                                                  |
| 8081 | Keycloak                                               |
| 8082 | 決済代行の WireMock（Playwright が管理 API を呼ぶ）    |

- 5173 と 8080 は開発用の Vite と Keycloak と同じ port である。
  `task e2e` は開始時に両方を確かめ、使用中なら止めるよう示して失敗する。
  5433、6380、8081、8082 は compose-test だけが使うため、開始時には確かめない。
- `task test` と同じ Compose project（`spring-modulith-test`）を使うため、同時に実行しない。
- 前提の道具は `task setup` と同じである。
  Chromium は `task e2e` が導入する。
  OS の依存ライブラリは、初回に `sudo` 付きの `vp exec playwright install-deps chromium` で入れる。

## 実行と後片付け

`task e2e` は次の順に実行する。

1. 前回残した環境を破棄し、5173 と 8080 が空いていることを確かめる。
2. frontend の依存の導入と build、Chromium の導入、backend イメージの build を行う。
   frontend は `vp build --mode test` で build し、`.env.test` の `FRONTEND_OTEL_ENABLED=true` で Faro を有効にする。
3. PostgreSQL、Redis、Keycloak、WireMock を起動し、`task be-migrate` で migration を適用する。
4. backend を起動し、ホストから readiness を確かめる。
5. Playwright を実行する。
   Playwright が `webServer` で Vite preview を起動し、終了時に止める。
6. 成否にかかわらず、コンテナと volume を削除する。

E2E には Collector を置かず、テストが `/collect` を `page.route` で応答する（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
`frontend/e2e/logout.spec.ts` は、`/collect` が 503 を返してもログインとログアウトのフォームを送信できることを確かめる。
`frontend/e2e/tracing.spec.ts` は、この stub のもとで要求の `traceparent` を観測し、同一オリジンの `/api/**` にだけ sampled flag が 1 の値が付き、`/api/**` 以外のパス、`/collect`、IdP には付かないことを確かめる。

起動済みの環境に対して Playwright だけを実行するときは、`frontend/` で `pnpm e2e`（`vp run e2e` と同じ）を実行する。
build、コンテナの起動、後片付けは行わない。

- ログイン情報は、ルートの `.env.test` の `E2E_USERNAME` と `E2E_PASSWORD` から読む。
  試しに値を変えるときは、Git から除外されたルートの `.env.test.local` に書く。
- `.env.test` は、Git で管理する `.env.test.example` からコピーして作る Git 除外のファイルである。
  `task e2e` と `task fe-knip`（Knip が Playwright の設定を読む）は、`.env.test` が無ければ `.env.test.example` からコピーする。
- `task e2e` は `TEST_ENV_FILE` の上書きに対応せず、`.env.test` を固定で読む。
  compose の backend と Playwright が `.env.test` を固定で読むためである。
- ローカルで `E2E_KEEP_ENV=1 task e2e` を実行して失敗したときだけ、コンテナと volume を残す。
  残したときは、backend のログ、`pnpm e2e` での再実行、片付けのコマンドを表示する。
  片付けは `docker compose -f docker/compose-test.yml --profile e2e down --volumes --remove-orphans` で行う。
- `CI` が空でなければ、`E2E_KEEP_ENV` にかかわらず片付ける。
- Ctrl-C で中断すると、後片付けが走らないことがある。
  次の `task e2e` が開始時に破棄するが、すぐに片付けるときは上の `down` のコマンドを実行する。

## 失敗の調べ方

- `frontend/playwright-report/` の HTML report を開く。
- `frontend/test-results/` の trace を `vp exec playwright show-trace <trace.zip>` で開く。
- 失敗時は `task e2e` が backend のログを `frontend/test-results/backend.log` に書く。
  `test-results/` は Playwright の出力先のため、`pnpm e2e` を再実行すると `backend.log` も消える。
  残した環境では `docker compose -f docker/compose-test.yml --profile e2e logs backend` で読む。
- CI では、失敗時に `e2e-results` artifact に report、trace、`backend.log` を保存する。

## CI

`.github/workflows/e2e.yml` は、Pull Request で frontend、backend（DB の changeset を含む）、Keycloak の設定、compose-test とその入力（`docker/initdb/`、`.env.test.example`）、Taskfile、この workflow 自体を変えたときだけ `task e2e` を実行する。
retry は 2 回で、失敗時に Playwright の成果物と backend のログを保存する。
この check は required status checks に登録しない（[ブランチ保護](../repository/branch-protection.md)）。
