---
type: ADR
title: 'ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る'
description: 本番のコードには実際の HTTP の Client だけを置き、ローカルとテストでは版を固定した WireMock が外部システムを偽り、失敗は注文ごとのスタブを実行中に足して作る決定。
tags: [adr, backend, testing, external-system, wiremock]
---

# ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る

## Status

Accepted

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
- 失敗と業務上の拒否は、冪等性キーに一致する注文ごとのスタブで、共有のスタブより優先度を高くして作る。
  E2E は管理 API（`POST /__admin/mappings`、`DELETE /__admin/mappings/{id}`）で、結合テストは Java の DSL で、実行中に足して取り除く。
- Client の単体テストは JDK の `HttpServer` のままにする。
  WireMock（`org.wiremock:wiremock-standalone` 3.13.2、`testImplementation` だけ）は、リトライとサーキットブレーカーを確かめる Spring の結合テストと E2E に使う。
- 接続先の URL に既定値を置かず、未設定なら起動に失敗させる。
  WireMock の URL は env の例と compose ファイルにだけ書き、本番が既定値のまま WireMock を向くことを防ぐ。
  明示した誤った値（平文の `http://` や誤ったホスト）は起動の時点で止まらず、デプロイの設定のレビューに依存する。
  WireMock はローカル、テスト、E2E だけで使い、本番には置かない。
- 決済代行の呼び出しの結果を次の四つに分け、再投入で回復できるものだけをイベント出版の `FAILED` に残す。
  回復不能なエラーの扱いは [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md) に従う。
  1. 一時障害（接続の失敗、タイムアウト、429、5xx）：サーキットブレーカーの失敗に数え、例外を Client の外へ投げ、イベント出版を `FAILED` のまま再投入を待つ。
  2. 業務上の拒否（カードの拒否、限度額の超過）：Client は例外を投げずに拒否を表す結果を返し、呼び出し元が決済の拒否を業務の状態に記録する。
     リスナーは正常終了し、イベント出版は `COMPLETED` になる。
  3. 資格情報の不備（401、403）：サーキットブレーカーの失敗に数えず、例外を Client の外へ投げ、イベント出版を `FAILED` に残す。
     資格情報を直したあとの再投入で回復できるためである。
  4. 契約の不備（1 と 3 以外の 4xx）：サーキットブレーカーの失敗に数えず、回復不能なエラーとして決済の失敗を業務の状態に記録し、リスナーを正常終了させる。
     同じ要求を再投入しても回復しないためである。
- サーキットブレーカーの `payment-gateway` の instance は、429 以外の `HttpClientErrorException` を無視する述語を `ignore-exception-predicate` に指定する。
  `ignore-exceptions` に `HttpClientErrorException` を書くと、[ADR-019](ADR-019-define-resilience-and-capacity-guardrails.md) が一時障害に挙げる 429 まで無視するためである。
  Resilience4j では `ignore-exceptions` が `record-exceptions` より優先され、型の列挙では 429 だけを失敗に数えられない。
- `retry` の `payment-gateway` の instance は `default`（試行 1 回）を継承し、どの結果も再試行しない。
  `idempotent` を継承する instance は、`retry-exceptions` に ADR-019 の再試行の対象だけを書く。
  対象は、接続の失敗とタイムアウトの `ResourceAccessException`、`HttpClientErrorException$TooManyRequests`、`HttpServerErrorException$BadGateway`、`HttpServerErrorException$ServiceUnavailable`、`HttpServerErrorException$GatewayTimeout` である。
- backend の Gradle の `test` タスクは、`docker/wiremock` を入力（`inputs.dir`）に宣言する。
  結合テストがそこのスタブを読むため、スタブを変えるとテストがやり直される。
- compose-test の WireMock には profile を付けず、`task test` と `task e2e` の両方で起動する。
  Spring のテストの一部が、`.env.test` の `PAYMENT_GATEWAY_BASE_URL` を通して compose-test の WireMock の共有のスタブを呼ぶためである。
- E2E は、setup project の開始時に `POST /__admin/mappings/reset` を一度呼ぶ。
  前の実行で後片付けが走らずに残った注文ごとのスタブを持ち越さないためである。

決済代行の偽物の HTTP の契約は次のとおりである。
実際の決済代行の契約が決まるまでは、このリポジトリが決めた契約である。

- 要求は `POST /v1/charges` で、本文は `{orderId, amount, currency: "JPY"}`、ヘッダーは `Idempotency-Key: <orderId>` である。
- 成功の応答は `201 {chargeId, status: "SUCCEEDED"}` である。
- 拒否の応答は `201 {chargeId, status: "DECLINED"}` である。
  Client は `status` が `DECLINED` なら、例外を投げずに拒否を表す結果を返す。

## Consequences

### Positive

- 本番のコードに偽物の状態がなくなり、mode の設定で本番を失敗し続ける状態にできない。
- 決済の拒否と契約の不備が再投入の列に積まれず、イベント出版の `FAILED` は再投入で回復できるものだけになる。
- 5xx、タイムアウト、遅延、サーキットブレーカーの open を、実際の HTTP を通して確かめられる。
- backend を再起動せずに、シナリオの途中で注文ごとに応答を切り替えられる。
- 成功のスタブが 1 ファイルなので、Client と偽物の契約がずれにくい。
  スタブを変えると、Gradle の入力の宣言により backend のテストがやり直される。
- WireMock は `runtimeClasspath` と bootJar に入らない。

### Negative

- コンテナが 1 つ増え、ホストのポート（開発用 8090、compose-test 8082）を使う。
  compose-test の 8082 は 5433、6380、8081 と同じく compose-test だけが使うため、`task e2e` の開始時の空きの確認には足さない。
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

- 未決事項として、次の三つを実際の決済代行の契約を選ぶときに決める。
  今の `base-url` は `http://` を受け付け、Client は資格情報を送らず、起動時の検査も置かない。
  1. TLS の強制：本番は `https://` にする。
     `https://` と許可するホストを検査する場所（アプリの起動のコードでなく、デプロイの設定や IaC のレビュー）と、証明書のピン留めや独自のトラストストアの要否を決める。
     このリポジトリにはまだ IaC が無く、決まるまで検査は無い。
  2. 資格情報の扱い：決済代行が求める認証の方式（API キーのヘッダー、OAuth 2.0 の client credentials、mTLS）を決める。
     秘密の取得元（[ADR-008](ADR-008-single-application-yaml-external-config.md) に従い、ECS タスク定義の `secrets` で Secrets Manager などから注入する環境変数）、ローテーション、ログに出さない方法も決める。
  3. 契約の分類：拒否の表し方（2xx の本文か、402 などの 4xx か）と、各 HTTP の状態を Decision の四つの分類のどれに当てるかを決める。
     決まるまでは、このリポジトリの暫定の契約と分類に従う。
- 本番の URL の誤設定は、未設定を除いて起動の時点で止まらず、未決事項の 1 で検査の場所を決めるまで運用のレビューに依存する。
- backend を通して決済の失敗と再投入を確かめる E2E は、後続の作業（[#167](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/167)）で書く。
- 規約文書（`docs/backend/class-roles/external-client.md`、`docs/backend/testing-strategy.md`、`docs/container/compose.md`、`docs/e2e/testing-strategy.md`、`docs/integration/async-failure-recovery.md`）は、この ADR を Accepted にする #122 の変更で更新した（[ADR の運用ルール](conventions.md)）。

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
- [ADR の運用ルール](conventions.md)
- [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る](ADR-057-adopt-playwright-for-e2e-tests.md)
- [クラスの役割：外部システムの Client](../backend/class-roles/external-client.md)
- [Compose の作り方](../container/compose.md)
- [E2E テストの方針と書き方](../e2e/testing-strategy.md)
- WireMock Docs, Running in Docker（<https://wiremock.org/docs/standalone/docker/>）
- WireMock Docs, Admin API Reference（<https://wiremock.org/docs/standalone/admin-api-reference/>）
- WireMock Docs, Stubbing（<https://wiremock.org/docs/stubbing/>）
