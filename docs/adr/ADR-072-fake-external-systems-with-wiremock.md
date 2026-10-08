---
type: ADR
title: 'ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る'
description: 本番のコードには実際の HTTP の Client だけを置き、ローカルとテストでは版を固定した WireMock が外部システムを偽り、失敗は注文ごとのスタブを実行中に足して作る決定。
tags: [adr, backend, testing, external-system, wiremock]
---

# ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る

## Status

Proposed

## Date

2026-10-08

## Context

[#122](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/122) のサンプルの注文と決済は、決済代行を呼ぶ `PaymentGatewayClient` の偽物を本番のコードに持ち、`payment-gateway.mode` の設定で成功と失敗を切り替えていた。
[#164](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/164) は、この形に三つの問題があると指摘した。
一つ目に、本番のコードを失敗する状態に設定できる。
二つ目に、失敗の種類が一つしかなく、5xx、タイムアウト、遅延を区別して確かめられない。
三つ目に、切り替えには backend の再起動が要り、シナリオの途中で応答を変えられない。

実際の決済代行の契約が決まり、本物の Client を置いても、偽物を本番のコードに残す限り同じ問題が残る。
タイムアウト、リトライ、サーキットブレーカーの値は [ADR-019](ADR-019-define-resilience-and-capacity-guardrails.md) が、Client の役割は [ADR-050](ADR-050-define-backend-class-roles-and-naming.md) が定めており、それらが実際の HTTP の失敗で働くことを確かめる手段が要る。

## Decision

外部システムの偽物を本番のコードに置かず、ローカルとテストでは WireMock が外部システムを偽る。
具体的には次のようにする。

- 本番のコードは、実際の HTTP の Client だけを持つ。
  Client は既定値のない接続先の URL、タイムアウト、名前付きの Resilience4j の instance で構成し、偽物の分岐や mode の設定を持たない。
- ローカルとテストでは、`docker/compose.yml` と `docker/compose-test.yml` で版を固定した WireMock のイメージを起動し、接続先の URL をそこへ向ける。
- 成功のスタブは `docker/wiremock/mappings/` の 1 つのマッピングファイルにし、2 つの compose ファイルと JVM の中の結合テストが共有する。
- 失敗は、冪等性キーに一致する注文ごとのスタブで、共有のスタブより優先度を高くして作る。
  E2E は管理 API（`POST /__admin/mappings`、`DELETE /__admin/mappings/{id}`）で、結合テストは Java の DSL で、実行中に足して取り除く。
- Client の単体テストは JDK の `HttpServer` のままにする。
  WireMock（`org.wiremock:wiremock-standalone` 3.13.2、`testImplementation` だけ）は、リトライとサーキットブレーカーを確かめる Spring の結合テストと E2E に使う。
- 本番の URL が WireMock を指すことは、URL に既定値を置かず未設定なら起動に失敗させることと、WireMock の URL を env の例と compose ファイルにだけ書くことで防ぐ。
  WireMock はローカル、テスト、E2E だけで使い、本番には置かない。
- 決済代行の 4xx は、サーキットブレーカーの失敗に数えず、再試行もしない。
  4xx はこちらの要求や契約の誤りであり、決済代行の不調を示さないためである。
  `payment-gateway` の instance の `ignore-exceptions` に `HttpClientErrorException` を書き、5xx、タイムアウト、接続の失敗だけを失敗に数える。
  `payment-gateway` の `retry` の instance は `default`（試行 1 回）を継承し、例外の除外を書かないため、4xx も 5xx も再試行しない。
  4xx を再試行しないのは `default` を継承するためであり、`idempotent`（試行 3 回、例外の除外なし）を継承する instance は 4xx も再試行する。
  `idempotent` を継承する instance は、`retry` の `ignore-exceptions` に `HttpClientErrorException` を書いて 4xx を明示して除く。
  4xx の例外は Client の外へ投げられ、決済は記録されず、イベント出版は 5xx と同じく FAILED のまま再投入を待つ。

決済代行の偽物の HTTP の契約は次のとおりである。
実際の決済代行の契約が決まるまでは、このリポジトリが決めた契約である。

- 要求は `POST /v1/charges` で、本文は `{orderId, amount, currency: "JPY"}`、ヘッダーは `Idempotency-Key: <orderId>` である。
- 成功の応答は `201 {chargeId, status: "SUCCEEDED"}` である。

## Consequences

### Positive

- 本番のコードに偽物の状態がなくなり、設定の誤りで本番が失敗し続ける状態を作れない。
- 5xx、タイムアウト、遅延、サーキットブレーカーの open を、実際の HTTP を通して確かめられる。
- backend を再起動せずに、シナリオの途中で注文ごとに応答を切り替えられる。
- 成功のスタブが 1 ファイルなので、Client と偽物の契約がずれにくい。
  スタブを変えると、Gradle の入力の宣言により backend のテストがやり直される。
- WireMock は `runtimeClasspath` と bootJar に入らない。

### Negative

- コンテナが 1 つ増え、ホストのポート（開発用 8090、compose-test 8082）を使う。
- JDK の `HttpClient` が平文の HTTP で HTTP/2 への upgrade（h2c）を試み、WireMock（Jetty）では本文付きの POST が切れた。
  偽物の側で compose の `--disable-http2-plain` と結合テストの `http2PlainDisabled(true)` を指定する必要がある。
- テストの依存の jar の版とイメージのタグを、人の手で同じに保つ必要がある。
  Dependabot は両者を別々の Pull Request で更新する。
- `@RegisterExtension` の `WireMockExtension` は `@DynamicPropertySource` と組み合わせられず、static の初期化でサーバを起動する。
  Spring のコンテキストが URL を読む時点で、拡張がサーバを起動していないためである。
- `PAYMENT_GATEWAY_BASE_URL` を持たない古い `.env.test` では、Spring のテストが `${PAYMENT_GATEWAY_BASE_URL}` のプレースホルダーを解決できず失敗する。
  E2E は、`frontend/e2e/environment.ts` の `requireEnv` が失敗する。
  Task のテスト系タスクは、その前に `.env.test.example` にあって `.env.test` に無い変数の名前を並べて止まる。
  移行では、既存の `.env.test` に `PAYMENT_GATEWAY_BASE_URL=http://127.0.0.1:8082` を、既存の `.env` に `.env.example` の `PAYMENT_GATEWAY_BASE_URL` の行を足す。

### Neutral

- 429 も `HttpClientErrorException` のため、サーキットブレーカーの失敗に数えない。
  Resilience4j では `ignore-exceptions` が `record-exceptions` より優先され、設定だけでは 429 だけを失敗に数えられない。
  決済代行の流量制限の契約が決まったら、429 だけを数える `ignore-exception-predicate` に替える。
- 未決事項として、本番の接続の TLS と資格情報を、実際の決済代行の契約を選ぶときに決める。
  今の `base-url` は `http://` を受け付け、Client は資格情報を送らず、起動時の検査も置かない。
  1. TLS の強制：本番は `https://` にする。
     強制する場所（アプリの起動のコードでなく、デプロイの設定や IaC のレビュー）と、証明書のピン留めや独自のトラストストアの要否を決める。
  2. 資格情報の扱い：決済代行が求める認証の方式（API キーのヘッダー、OAuth 2.0 の client credentials、mTLS）を決める。
     秘密の取得元（[ADR-008](ADR-008-single-application-yaml-external-config.md) に従い、ECS タスク定義の `secrets` で Secrets Manager などから注入する環境変数）、ローテーション、ログに出さない方法も決める。
- 本番の profile で URL を検査する仕組みは、未設定で起動に失敗すること以外に持たない。
- backend を通して決済の失敗と再投入を確かめる E2E は、後続の作業（[#167](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/167)）で書く。

## Alternatives Considered

### 選択肢1: 偽物を本番のコードに残し、mode の設定で切り替える

- **Description**：#122 の形のまま、`payment-gateway.mode` で成功と失敗を切り替える偽物の Client を本番のコードに置く。
- **Pros**：コンテナを増やさず、追加の依存もない。
- **Cons**：本番のコードを失敗する状態に設定でき、失敗の種類が一つで、切り替えに再起動が要る（#164 の三つの問題）。

### 選択肢2: Client の単体テストも WireMock に移す

- **Description**：JDK の `HttpServer` を使う Client の単体テストを、WireMock に置き換える。
- **Pros**：偽物の書き方が一種類にそろう。
- **Cons**：`HttpServer` は依存も Spring の起動も要らず、既存の規約の例もそれを使う。
  WireMock が価値を持つのは、リトライとサーキットブレーカーが働く Spring の結合テストからである。

### 選択肢3: 失敗の固定のマッピングファイルを置くか、設定を変えて backend を再起動する

- **Description**：失敗の応答を返すマッピングファイルをあらかじめ置くか、別の設定で backend を起動し直す。
- **Pros**：管理 API を呼ぶ補助のコードが要らない。
- **Cons**：シナリオの途中で応答を切り替えられない。
  全体に効く失敗は、並列のテストと他の注文まで失敗させる。

### 選択肢4: 結合テストで Testcontainers の WireMock のモジュールを使う

- **Description**：Spring の結合テストで、WireMock のコンテナを Testcontainers で起動する。
- **Pros**：compose と同じイメージで結合テストを実行できる。
- **Cons**：依存がもう一つ増える。
  同じマッピングのディレクトリを読む JVM の中のサーバで足り、JVM のテストにコンテナは要らない。

## References

- [#164 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/164)
- [#166 参照業務機能を main に導入し、開発基盤を継続的に検証する](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/166)
- [#122 開発基盤を確かめる注文と決済のサンプル機能を追加する](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/122)
- [#167 参照業務機能の主要フローと障害回復を検証する](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/167)
- [ADR-008: application.yaml を単一にし、設定を外部から注入する](ADR-008-single-application-yaml-external-config.md)
- [ADR-019: 外部連携の耐障害性と容量制御を標準化する](ADR-019-define-resilience-and-capacity-guardrails.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る](ADR-057-adopt-playwright-for-e2e-tests.md)
- [クラスの役割：外部システムの Client](../backend/class-roles/external-client.md)
- [Compose の作り方](../container/compose.md)
- [E2E テストの方針と書き方](../e2e/testing-strategy.md)
- WireMock Docs, Running in Docker（<https://wiremock.org/docs/standalone/docker/>）
- WireMock Docs, Admin API Reference（<https://wiremock.org/docs/standalone/admin-api-reference/>）
- WireMock Docs, Stubbing（<https://wiremock.org/docs/stubbing/>）
