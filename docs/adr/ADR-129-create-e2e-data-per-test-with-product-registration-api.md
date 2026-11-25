---
type: ADR
title: 'ADR-129: E2E のデータを各テストが作り、商品は本番の商品を登録する API で作る'
description: E2E が開発用のシーダーの代表データを読むのをやめ、各テストが自分専用のデータを作る決定。商品は、本番に足す商品を登録する API（POST /api/products）で作り、E2E のためだけの入口を作らない。登録の権限はテナントを選んだ利用者なら誰でもとし、制限は別の Issue に分ける。ADR-073 を置き換える。
tags: [adr, e2e, testing, seed, product, web-api]
---

# ADR-129: E2E のデータを各テストが作り、商品は本番の商品を登録する API で作る

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

注文、決済済み、拒否、再投入待ち、取消済みは、画面、公開 API、決済代行の WireMock（`failChargesFor`）で作れる。
ADR-073 が代表の注文で確かめていた決済済み、拒否、再投入待ちの詳細は、既存の「作成して確定」「拒否」「5xx」と「タイムアウト」のテストが同じ表示を確かめている。
公開 API で作れない状態は商品だけだった。

この ADR の最初の版は、商品を別プロセスの fixture サーバーで作る決定だった。
backend のテストのソースに置く `E2eFixtureServer` を Playwright の `webServer` で起動し、`POST /products` で商品の行を直接入れる。
試作は動き、`fullyParallel` の `task e2e` が全件通るところまで確かめたが、重さに対して得るものが小さいと判断して取り下げた。

- **変更の量**：fixture サーバーとその周りは 12 の commit で約 1,200 行を足し、うちコードと設定が約 1,000 行だった。
- **別プロセス**：Playwright が起動して止めるプロセスと、slot ごとの port（`E2E_FIXTURE_PORT`）と、HTTP の契約（`POST /products`、`GET /health`）が増えた。
- **Java 25 の要件**：fixture サーバーはテストと同じ toolchain の java で起動する必要があり、Gradle の `writeE2eFixtureArgs` で java のパスと引数ファイルを書いて渡した。
- **境界の検査**：ブラウザの別のサイトからの要求を通さないよう、`Host` の検査、`Content-Type` と本文の大きさの上限、想定外の失敗の 500 を作り、`E2eFixtureServerTest` で確かめる必要があった。
- **本番の経路の迂回**：商品の行は `TestProducts.insert` が直接入れるので、入力の検証、`TableWriter`、`*_pgm_cd` の束縛を通らない。

最初の版は、商品の管理 API を本番に足す案を、業務の要件に無い API と認可の判断が増え、本番のコードに E2E のための入口が残るとして採らなかった。
所有者は、この判断を次のとおり改めた。
商品の登録は商品のマスタを持つ業務の操作であり、E2E のためだけの入口ではない。
作成だけに絞れば、足すコードは既存の書き込みの経路（`TableWriter`、モジュールのロール、RLS、共通カラム）に乗る。
認可は、業務の API を役割で分ける仕組みがバックエンドにまだ無い（業務の API は、テナントを選んだかどうかだけを見る）ため、この ADR では決めずに別の Issue に分ける。

ADR-073 の後に入った [ADR-126](ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md) は Proposed で、契約だけが先に入っている。
`OpenApiConfig` の customizer が、GET と HEAD 以外の業務の operation に必須の `Idempotency-Key` を付けるが、サーバーはまだキーを検査せず、受ける側のコードも無い。

## Decision

E2E は開発用のシーダーを使わない。
各テストが自分専用のデータを作り、共有するのはテナントと所属、Keycloak の利用者だけにして、それらは読むだけにする。

- 注文は、今までどおり画面か公開 API で作る。決済代行の拒否、5xx、遅延は WireMock の `failChargesFor` で作る。
- テナントと所属は、`task e2e-up` が `task be-seed-tenancy`（シーダーの `tenancy` のモード）で入れ、業務データを入れない。
- 本番に、商品を登録する API（`POST /api/products`、operationId は `registerProduct`）を足す。
  作成だけを足し、更新、削除、画面は作らない。
  本文は `productCode`（1 から 20 文字）、`productName`（1 から 100 文字）、`unitPrice`（0 以上で小数 2 桁まで）、`salesStatus`（`ON_SALE` か `DISCONTINUED`）で、201 と `Location` を返す。
  商品コードの重複は、注文の作成の客先注文番号と同じく、`/problems/unique-violation` の 409 で返す。
  書き込みは `TableWriter` を通し、`product` のロール、テナントの RLS、共通カラムを既存の書き込みと同じ仕組みで通す。
- 商品を登録できるのは、テナントを選んだ認証済みの利用者なら誰でもとする（注文の作成と同じ扱い）。
  ADMIN だけに制限する仕組みはこの ADR で作らず、業務の API の認可と合わせて別の Issue で決める。
- この API は `Idempotency-Key` を検査しない。
  契約は ADR-126 の customizer が付けるとおり必須にし、Playwright の補助は契約に従ってキーを送る。
  サーバーの検査は ADR-126 の実装に任せる。
- テストのための入口を本番に作らない。
  Profile や E2E のためだけのプロパティで入口を切り替えない。
- 商品は、Playwright の `productFactory` fixture が、ログインしてテナントを選んだ page の Cookie で `POST /api/products` を呼んで作る。
  CSRF の token（`__Host-XSRF-TOKEN` の値を `X-XSRF-TOKEN` に付ける）は補助の中でだけ付け、spec が確かめる「更新の要求は token が無ければ 403」と分ける。
  商品コードは `E2E-` に UUID の 16 進 16 文字を足した一意な値で、商品名はその商品コードを含む。
- `task lint-e2e-seed` が、`frontend/apps/web/e2e/` の `SEED-` の文字列を失敗にする。
- 開発用の代表データを読む検証スキル（verify-demo）は、`stack.sh up` で `task be-seed` を実行し、代表データを読む補助を自分で持つ。

この ADR は ADR-073 を置き換える。

## Consequences

### Positive

- シーダーの商品名、件数、代表の注文を変えても、E2E が前提にするデータは変わらない。
- テストが使うデータは、そのテストの中で作られて見える（Mystery Guest が無い）。
- E2E の商品は、本番と同じ入力の検証、書き込みの経路、監査の記録を通る。
- E2E のためのプロセス、port、HTTP の契約、Java の起動の手順が増えない。
- spec が代表データを読む書き方に戻ると、`task fe-check` が落ちる。

### Negative

- 制限を入れるまで、テナントの MEMBER も商品を登録できる。
  商品はテナントのマスタで、本来は管理者が登録する操作である。
- 登録した商品を更新、削除する手段が無い。
  誤って登録した商品は、販売終了にもできない。
- 実行ごとに商品が増え、注文の一覧と商品の一覧には並列のテストのデータも並ぶ。spec は自分の商品名と客先注文番号で絞って数える。

### Neutral

- ADR-126 のサーバーの検査を入れるときに、商品の登録も対象に入る。
- DB は実行ごとに作って捨てる。E2E は別プロセスの backend を HTTP で通り、要求ごとにコミットされるため、ロールバックは使わない。
- Playwright は TypeScript のままにする。

## Alternatives Considered

### 選択肢1: 別プロセスの fixture サーバーで商品を作る

- **Description**：この ADR の最初の版の決定。backend のテストのソースに置く fixture サーバーを Playwright の `webServer` で起動し、`POST /products` で商品の行を直接入れる。
- **Pros**：本番のコードに書き込みの API を足さない。
- **Cons**：Context に書いたとおり、約 1,000 行のコードと設定、別プロセス、Java 25 の起動の手順、境界の検査を持ち、商品の行は本番の検証と書き込みの経路を通らない。重さに対して得るものが小さいため採らない。

### 選択肢2: Playwright の fixture から Node で DB に直接入れる

- **Description**：Playwright の fixture が PostgreSQL に接続し、SQL で商品を入れる。
- **Pros**：プロセスも本番の API も増えない。
- **Cons**：スキーマ、モジュールのロール、RLS のテナントの設定、共通カラムの知識を TypeScript に複製するため採らない。

### 選択肢3: テスト専用の入口を、Profile かプロパティで有効にする

- **Description**：本番のコードに商品を作る入口を置き、Spring の Profile か E2E のためだけのプロパティで、テストのときだけ有効にする。
- **Pros**：本番の利用者からは入口が見えない。
- **Cons**：[ADR-008](ADR-008-single-application-yaml-external-config.md) は環境差を Profile ではなく環境変数で表し、Profile ごとの設定を避けている。設定を誤れば本番でも入口が開き、テストのための分岐が本番のコードに残るため採らない。

### 選択肢4: ADR-073 のまま、シーダーの代表データを読む

- **Description**：シーダーを E2E と開発で共有し続ける。
- **Pros**：変更が要らない。
- **Cons**：シーダーの変更が E2E の前提を気づかれないまま変える問題が残るため採らない。

## References

- [#612 E2E のデータを各テストが作る方式に変え、開発用シーダーへの依存をなくす](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/612)
- [ADR-073: E2E はシーダーの代表データを読むだけにし、変更するデータは各テストが作る](ADR-073-read-seeded-data-in-e2e.md)（この ADR が置き換える）
- [ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る](ADR-057-adopt-playwright-for-e2e-tests.md)
- [ADR-008: application.yaml を単一にし、設定を外部から注入する](ADR-008-single-application-yaml-external-config.md)
- [ADR-096: 共通カラムの作成者と更新者には、AppUserPrincipal の利用者の ID だけを登録する](ADR-096-require-app-user-principal-for-audit-columns.md)
- [ADR-107: 業務テーブルを RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- [ADR-126: GET と HEAD 以外のすべての業務の API に Idempotency-Key を必須にし、モジュールごとの表に Result を保存する](ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md)
- [E2E テストの方針と書き方](../e2e/testing-strategy.md)
