---
type: ADR
title: 'ADR-129: E2E のデータを各テストが作り、商品は本番の商品を登録する API で作る'
description: E2E が開発用のシーダーの代表データを読むのをやめ、各テストが自分専用のデータを作る決定。商品は、本番に足す商品を登録する API（POST /api/products）で作り、E2E のためだけの入口を作らない。登録の権限はテナントを選んだ利用者なら誰でもとし、制限は #758 に分ける。ADR-073 を置き換える。
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
ADR-073 が代表の注文で確かめていた詳細の表示は、各テストが自分で作った注文で確かめられる。
決済済みと拒否は、「作成して確定」と「拒否」のテストが、確定の直後の詳細と、一覧から開いた詳細の両方で確かめる。
一覧から開く経路は、この 2 つのテストにだけある。
再投入待ち（`SEED-C06`）は、「5xx」と「タイムアウト」のテストが、確定の直後の詳細で確かめる。
公開 API で作れない状態は商品だけだった。

商品の登録は商品のマスタを持つ業務の操作であり、E2E のためだけの入口ではない。
作成だけに絞れば、足すコードは既存の書き込みの経路（`TableWriter`、モジュールのロール、RLS、共通カラム）に乗る。
認可は、業務の API を役割で分ける仕組みがバックエンドにまだ無い（業務の API は、テナントを選んだかどうかだけを見る）ため、この ADR では決めずに [#758](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/758) に分ける。

ADR-073 の後に入った [ADR-126](ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md) は Proposed である。
GET と HEAD 以外の業務の API は `Idempotency-Key` を必須にし、サーバーは書き込みの API を持つモジュールのスキーマのキーの表で検査する。
商品を登録する API を足すと、`product` のスキーマにもキーの表が要る。

Proposed の間に Decision を書き直した。
Pull Request #752 のレビューを受けて、商品の登録のキーの検査を [#759](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/759) に分けず、この ADR の範囲に含めた。

## Decision

E2E は開発用のシーダーを使わない。
各テストが自分専用のデータを作り（xUnit Test Patterns の Fresh Fixture）、共有するのはテナントと所属、Keycloak の利用者だけにして、それらは読むだけにする。

- 注文は、今までどおり画面か公開 API で作る。決済代行の拒否、5xx、遅延は WireMock の `failChargesFor` で作る。
- テナントと所属は、`task e2e-up` が `task be-seed-tenancy`（シーダーの `tenancy` のモード）で入れ、業務データを入れない。
- 本番に、商品を登録する API（`POST /api/products`、operationId は `registerProduct`）を足す。
  作成と、作成した商品 1 件の参照（`GET /api/products/{productId}`、operationId は `findProductById`）を足し、更新、削除、画面は作らない。
  本文は `productCode`（1 から 20 文字）、`productName`（1 から 100 文字）、`unitPrice`（0.01 以上で小数 2 桁まで）、`salesStatus`（`ON_SALE` か `DISCONTINUED`）で、201 と、商品 1 件の参照を指す `Location` を返す。
  商品コードの重複は、注文の作成の客先注文番号と同じく、`/problems/unique-violation` の 409 で返す。
  書き込みは `TableWriter` を通し、`product` のロール、テナントの RLS、共通カラムを既存の書き込みと同じ仕組みで通す。
- 商品を登録できるのは、テナントを選んだ認証済みの利用者なら誰でもとする（注文の作成と同じ扱い）。
  ADMIN だけに制限する仕組みはこの ADR で作らず、業務の API の認可と合わせて [#758](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/758) で決める。
- この API は、ADR-126 のとおり `Idempotency-Key` を必須にし、サーバーが検査する。
  キーと Result は `product` のスキーマのキーの表（`product.t_idempotency_key`）に保存し、同じキーの再送には 1 回目と同じ 201 と `Location` を返す。
  `productFactory` は、契約どおり呼ぶたびに新しいキーを送る。
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

- DB は実行ごとに作って捨てる。E2E は別プロセスの backend を HTTP で通り、要求ごとにコミットされるため、ロールバックは使わない。
- Playwright は TypeScript のままにする。

## Alternatives Considered

### 選択肢1: 別プロセスの fixture サーバーで商品を作る

- **Pros**：本番のコードに書き込みの API を足さない。
- **Cons**：E2E のたびに別の JVM を起動し、fixture サーバーの HTTP の契約と境界の検査を保守し続けることになる。本番の商品を登録する API で同じ商品を作れるため採らない。

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
- Gerard Meszaros『xUnit Test Patterns: Refactoring Test Code』（Addison-Wesley、2007）
  - [Obscure Test](http://xunitpatterns.com/Obscure%20Test.html)（原因の General Fixture と Mystery Guest）
  - [Fresh Fixture](http://xunitpatterns.com/Fresh%20Fixture.html)
