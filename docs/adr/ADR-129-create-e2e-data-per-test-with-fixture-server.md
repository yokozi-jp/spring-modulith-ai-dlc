---
type: ADR
title: 'ADR-129: E2E のデータを各テストが作り、商品はホストの fixture サーバーで作る'
description: E2E が開発用のシーダーの代表データを読むのをやめ、各テストが自分専用のデータを作る決定。公開 API の無い商品は、backend のテストのソースに置く fixture サーバーを Playwright の webServer で起動して作る。ADR-073 を置き換える。
tags: [adr, e2e, testing, seed, fixture]
---

# ADR-129: E2E のデータを各テストが作り、商品はホストの fixture サーバーで作る

## Status

Proposed

## Date

2026-11-25

## Context

[ADR-073](ADR-073-read-seeded-data-in-e2e.md) は、`task e2e` が開発用のシーダー（`LocalDataSeeder`、#114）で代表データを入れ、spec がその商品と代表の注文（`SEED-C01` から `SEED-C06`）を読む形にした。
商品に作成の公開 API が無く、空の DB では注文を作れなかったためである。

同じ代表データをローカル開発も使うので、開発の都合でシーダーを変えると、E2E が前提にする状態（`SEED-C03` の決済済み、`SEED-C05` の拒否、`SEED-C06` の再投入待ち、商品の並び）が気づかれないまま変わる。
気づくのは `task e2e` を流したときだけで、検査の仕組みは無かった（ADR-073 の Negative）。
xUnit Test Patterns の分類では、用途の違う者が共有する General Fixture と、テストから見えない場所でデータが決まる Mystery Guest にあたる（[#612](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/612)）。

[ADR-057](ADR-057-adopt-playwright-for-e2e-tests.md) は、テストデータを各テストが公開 API で作り、公開 API で作れない状態だけを用途を限定した DB fixture で作るとし、その判断を別の ADR に任せた。
この ADR がその判断である。

実装の前に、商品 1 件を作って 1 本の spec を通す試作で次を確かめた。

- **作った商品が公開 API から見える**：fixture サーバーの `POST /products` で作った商品は `GET /api/products` と作成の画面の選択肢に出て、注文を作れた。
- **並列で衝突しない**：fixture サーバーへ 30 並列で 60 件の `POST /products` を送ると、60 件とも 201 を返し、商品コードはすべて別で、`created_pgm_cd` はすべて `e2e.Fixture` だった。
  `*_pgm_cd` の束縛（`TestCommonColumns.runAs`）は `ScopedValue` でスレッドごとに分かれ、サーバーは要求ごとに仮想スレッドと DB の接続を使う。
  `fullyParallel` と 4 workers の `task e2e` は、3 回とも 34 件すべてが通った。
- **画面、公開 API、WireMock で作れない状態は商品だけである**：決済済みは確定、拒否と再投入待ちは確定の前の `failChargesFor`、取消済みは詳細の画面の「取り消す」で作れる。
  ADR-073 が代表の注文で確かめていた決済済み、拒否、再投入待ちの詳細は、既存の「作成して確定」「拒否」「5xx」と「タイムアウト」のテストが同じ表示を確かめている。
- **シーダーと共有できる範囲**：環境の確認（目印が `local` か `test` で、`DB_HOST` が loopback）と接続は `LocalDatabase` にまとめ、シーダーと fixture サーバーが使う。
  テナントと所属の投入（`TenancySeeds.insert`）、商品の行の登録（`TestProducts.insert`）、モジュールのロールとテナントの設定（`AsModule`）は、既存の補助をそのまま使う。
  シーダーの「空」の判定、reset、1 つのトランザクションでの投入は、シーダーだけに残す。
- **Node から DB へ直接入れる案（下の選択肢 2）は要らない**：上の 4 点が成り立ったため、試作以上には検討していない。

`task e2e-up` は以前から migration にホストの Java を使っており、fixture サーバーでホストの要件は増えない。

## Decision

E2E は開発用のシーダーを使わない。
各テストが自分専用のデータを作り、共有するのはテナントと所属、Keycloak の利用者だけにして、それらは読むだけにする。

- 注文は、今までどおり画面か公開 API で作る。決済代行の拒否、5xx、遅延は WireMock の `failChargesFor` で作る。
- 商品は、Playwright の `productFactory` fixture が fixture サーバーの `POST /products` で作る。
  商品コードは `E2E-` に UUID の 16 進 16 文字を足した一意な値で、商品名はその商品コードを含む。
- fixture サーバー（`E2eFixtureServer`）は backend のテストのソースに置き、bootJar と `runtimeClasspath` に入れない。
  Spring を起動せず、JDK の `HttpServer` を `127.0.0.1` にだけ開き、環境の目印がローカルかテストでなければ DB に触れずに止まる。
  起動時にテナントと所属を入れてから listen するので、`GET /health` が応答すれば共有のデータが揃っている。
- fixture サーバーは Playwright の `webServer` の 2 つ目として `tooling/e2e-fixture/serve.sh` から起動し、Playwright が止める。
  `task e2e-up` は Gradle の `writeE2eFixtureArgs` で java の引数ファイルを書くだけにし、`task be-seed` を実行しない。
  port は slot が `E2E_FIXTURE_PORT`（slot 0 は 8083）で決める。
- fixture サーバーに足すのは、画面、公開 API、WireMock で作れない状態が必要になったときだけにする。
- `task lint-e2e-seed` が、`frontend/apps/web/e2e/` の `SEED-` の文字列を失敗にする。
- 開発用の代表データを読む検証スキル（verify-demo）は、`stack.sh up` で `task be-seed` を実行し、代表データを読む補助を自分で持つ。

この ADR は ADR-073 を置き換える。

## Consequences

### Positive

- シーダーの商品名、件数、代表の注文を変えても、E2E が前提にするデータは変わらない。
- テストが使うデータは、そのテストの中で作られて見える（Mystery Guest が無い）。
- spec が代表データを読む書き方に戻ると、`task fe-check` が落ちる。

### Negative

- ホストに、Playwright が起動するプロセスと HTTP の契約（`POST /products`）が 1 つずつ増える。
- 商品の行の形は、fixture サーバーが使う `TestProducts.insert` が持つ。公開 API の検証を通らないので、商品のスキーマや不変条件を変えたら、この補助も直す。
- 実行ごとに商品が増え、注文の一覧と商品の一覧には並列のテストのデータも並ぶ。spec は自分の商品名と客先注文番号で絞って数える。

### Neutral

- DB は実行ごとに作って捨てる。E2E は別プロセスの backend を HTTP で通り、要求ごとにコミットされるため、ロールバックは使わない。
- Playwright は TypeScript のままにする。

## Alternatives Considered

### 選択肢1: Taskfile が fixture サーバーをバックグラウンドで起動し、PID のファイルで止める

- **Description**：`task e2e-up` が Gradle の JavaExec をバックグラウンドで起動し、後片付けで止める。
- **Pros**：Playwright の設定を変えない。
- **Cons**：止める場所が `task e2e`、`E2E_KEEP_ENV` の案内、verify-demo の `stack.sh down` の 3 か所に増え、Gradle の子プロセスが残りやすいため採らない。

### 選択肢2: Playwright の fixture から Node で DB に直接入れる

- **Description**：Playwright の fixture が PostgreSQL に接続し、SQL で商品を入れる。
- **Pros**：プロセスが増えない。
- **Cons**：スキーマ、モジュールのロール、RLS のテナントの設定、共通カラムの知識を TypeScript に複製するため採らない。

### 選択肢3: 商品の管理 API を本番に足す

- **Description**：商品を作る公開 API を足し、各テストが公開 API で商品を作る。
- **Pros**：業務の機能と同じ経路で作れる。
- **Cons**：業務の要件に無い API と認可の判断が増え、本番のコードに E2E のための入口が残るため採らない（ADR-073 の選択肢 1 と同じ理由）。

### 選択肢4: ADR-073 のまま、シーダーの代表データを読む

- **Description**：シーダーを E2E と開発で共有し続ける。
- **Pros**：変更が要らない。
- **Cons**：シーダーの変更が E2E の前提を気づかれないまま変える問題が残るため採らない。

## References

- [#612 E2E のデータを各テストが作る方式に変え、開発用シーダーへの依存をなくす](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/612)
- [ADR-073: E2E はシーダーの代表データを読むだけにし、変更するデータは各テストが作る](ADR-073-read-seeded-data-in-e2e.md)（この ADR が置き換える）
- [ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る](ADR-057-adopt-playwright-for-e2e-tests.md)
- [ADR-104: compose-test を使う実行ごとに slot を割り当て、worktree をまたいで同時に流す](ADR-104-allocate-test-stack-slots-per-run.md)
- [ADR-107: 業務テーブルを RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- [E2E テストの方針と書き方](../e2e/testing-strategy.md)
- [Playwright, Web server](https://playwright.dev/docs/test-webserver)
