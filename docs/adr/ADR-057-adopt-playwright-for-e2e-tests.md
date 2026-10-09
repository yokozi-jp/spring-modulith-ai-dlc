---
type: ADR
title: 'ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る'
description: 認証を含む主要な利用者の流れを、compose-test の backend と Vite preview に対して Playwright と Chromium で確かめる決定。テストデータは各テストが公開 API で作り、DB fixture と Datafaker のシーダーを使わない。
tags: [adr, e2e, playwright, testing, frontend]
---

# ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る

## Status

Proposed

## Date

2026-10-04

## Context

[ADR-027](ADR-027-adopt-frontend-testing-stack.md) は frontend のテストを Vitest と Testing Library で組み、Playwright は利用する要件が生じるまで追加しないとした。
開発基盤が整い、Keycloak でのログインを含む主要な利用者の流れを実ブラウザで確かめる要件が生じた。
unit と component のテストは MSW で backend を置き換えるため、認証の往復、同一 origin の proxy、Cookie の扱いを検証できない。

E2E の環境には、既存の判断による制約がある。
Keycloak の redirect URI は `localhost:5173` の origin に固定されている（[ADR-033](ADR-033-pin-vite-dev-origin-and-source-proxy-port.md)）。
SPA は同じ origin の proxy で backend の API と認証の URL へ到達する（[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)）。
Liquibase の migration はアプリケーションの起動から切り離されている（[ADR-005](ADR-005-decouple-liquibase-from-app-startup.md)）。
ローカルの開発用には Datafaker のシーダーがあり、E2E のデータと混ざると結果が実行環境に依存する。

## Decision

E2E テストに Playwright を使い、ブラウザは Chromium だけにする。
setup project が Keycloak の画面でログインし、保存した `storageState` を後続のテストで共有する。
`storageState` はコミットせず、CI の artifact にも入れない。

テストデータは各テストが公開 API で一意に作り、共有データを変更せず、実行順に依存させない。
公開 API で作れない状態が必要になったときだけ、用途を限定した DB fixture を別の ADR で判断する。
Datafaker のシーダーと E2E のデータを共有しない。

実行環境は `docker/compose-test.yml` の `e2e` profile とし、backend は既存の `backend/Dockerfile` からビルドして compose の network に置く。
backend は PostgreSQL、Redis、Keycloak へ service 名で接続し、compose が公開する port はすべて `127.0.0.1` に限る。

ブラウザは公開用の `127.0.0.1:8081`、backend は `keycloak:8080` で Keycloak へ到達するため、issuer を次のようにそろえる。

- Keycloak は hostname v2 の `KC_HOSTNAME=http://127.0.0.1:8081` で、issuer とブラウザ向けの endpoint を公開用の URL に固定する。
  `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` で、backend が直接呼ぶ token、jwks、userinfo の endpoint だけを要求の Host から決める。
- backend は `OIDC_DISCOVERY_URI` を設定したときだけ、discovery を `keycloak:8080` から読む `OidcDiscoveryLocationConfig` を有効にする。
  discovery の `issuer` が `OIDC_ISSUER_URI` と一致しなければ起動を止め、`ClientRegistrations.fromOidcConfiguration` で issuer と discovery の全体を registration に残す。
  これにより、ID トークンの `iss` の検査と、`end_session_endpoint` を使う OIDC のログアウトが、discovery を issuer から読む場合と同じに動く。

frontend は build した dist を Vite preview で配信し、ADR-033 と同じ `localhost:5173` と `strictPort` を preview にも適用する。
proxy は開発サーバーの定義を preview が引き継ぐ。

retry はローカルで 0 回、CI で 2 回とし、trace と screenshot は失敗時だけ保存し、video は保存しない。
ローカルでは `E2E_KEEP_ENV=1` を指定して失敗したときだけ環境を残し、CI では必ず片付ける。
E2E の CI は path filter 付きの非必須の check とする。

## Consequences

### Positive

- 認証を含む流れの回帰を実ブラウザで検出できる。
- 本番と同じ backend イメージと build 済みの frontend を検証できる。
- setup project のログインが毎回の実行で Keycloak の画面を通るため、後続のテストはログインを繰り返さずに済む。

### Negative

- E2E は 5173 と 8080 を使うため、開発用の Vite と Keycloak と同時に実行できない。
- CI の実行時間と backend イメージのビルドが増える。
- 失敗時の trace には、ダミーのパスワードと、片付けで破棄した環境のセッション Cookie が入りうる。
- backend の本番コードに、`OIDC_DISCOVERY_URI` を設定したときだけ有効になる認証の設定（`OidcDiscoveryLocationConfig`）が増える。
  有効なときは Boot の自動構成の `ClientRegistrationRepository` を置き換えるため、Boot の registration のプロパティを増やすときはこの設定も確かめる。
- Keycloak の hostname v2 の 2 つの設定と backend の設定の組み合わせを保つ必要がある。

### Neutral

- ログアウトを検証するテスト（#121）は、共有の `storageState` を使わず専用の browser context でログインする。
- preview の CSP は、本番の配信点と同じく `form-action` に IdP の origin を含める（#121）。
- 公開 API が増えた時点で、業務の流れのシナリオを追加する。

## Alternatives Considered

### 選択肢1: Cypress

- **Description**：Cypress で E2E を書く。
- **Pros**：対話的な実行画面が使いやすい。
- **Cons**：Keycloak の別 origin への遷移を含むログインに `cy.origin` が必要で、setup project と `storageState` に相当する仕組みが標準にないため採らない。

### 選択肢2: Vitest Browser Mode

- **Description**：既存の Vitest の Browser Mode で実ブラウザのテストを書く。
- **Pros**：道具が増えない。
- **Cons**：component を実ブラウザで描画する道具であり、ページの遷移と別 origin の IdP を含む流れを扱えないため採らない。

### 選択肢3: 複数ブラウザでの実行

- **Description**：Firefox と WebKit でも実行する。
- **Pros**：ブラウザ固有の不具合を見つけられる。
- **Cons**：ブラウザ固有の差は E2E の対象（主要な利用者の流れ）に関係が薄く、CI の時間が約 3 倍になるため採らない。

### 選択肢4: DB fixture または DataSeeder によるデータ投入

- **Description**：SQL の fixture や Datafaker のシーダーで E2E のデータを入れる。
- **Pros**：公開 API が無い状態も作れる。
- **Cons**：テストが DB のスキーマに依存し、公開 API の検証を迂回するため、必要になった時点で別の ADR で判断する。

### 選択肢5: host ネットワークの backend

- **Description**：backend を `network_mode: host` で動かし、ホストへ公開した port に `.env.test` の接続先のまま接続する。
- **Pros**：backend の設定を足さずに、ブラウザと同じ issuer（`127.0.0.1:8081`）で Keycloak へ到達できる。
- **Cons**：Docker Desktop では 4.34 以降の opt-in の設定が要り、Enhanced Container Isolation と両立しない。
  backend が全インターフェースで待ち受け、Compose の規約がアンチパターンとする `localhost` でのサービス間通信の例外にもなるため採らない。

### 選択肢6: Spring Boot のプロパティだけによる endpoint の指定

- **Description**：`issuer-uri` を外し、`authorization-uri`、`token-uri`、`jwk-set-uri` などを Boot のプロパティで個別に指定する。
- **Pros**：backend にクラスを足さずに済む。
- **Cons**：registration の `issuerUri` が空になり、ID トークンの `iss` を検査しなくなる。
  discovery の metadata も空になり、ログアウトが `end_session_endpoint` へ redirect しなくなるため採らない。

### 選択肢7: ホストの bootRun での backend の起動

- **Description**：ホストの Gradle で backend を起動する。
- **Pros**：イメージのビルドが要らない。
- **Cons**：本番のイメージを検証できず、バックグラウンドのプロセスの停止を Taskfile で管理する必要があるため採らない。

### 選択肢8: Taskfile でのバックグラウンドの preview の起動

- **Description**：Taskfile で Vite preview をバックグラウンドで起動し、終了時に停止する。
- **Pros**：Playwright の外で preview を再利用できる。
- **Cons**：`$!` と `kill` に頼る分だけ失敗経路が増え、停止漏れのプロセスが残る余地があるため、Playwright の `webServer` に起動と停止を任せる。

## References

- [Issue #116](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/116)
- [ADR-005](ADR-005-decouple-liquibase-from-app-startup.md)、[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)、[ADR-027](ADR-027-adopt-frontend-testing-stack.md)、[ADR-033](ADR-033-pin-vite-dev-origin-and-source-proxy-port.md)
- [E2E テストの方針と書き方](../e2e/testing-strategy.md)
- [ADR-073: E2E はシーダーの代表データを読むだけにし、変更するデータは各テストが作る](ADR-073-read-seeded-data-in-e2e.md)（テストデータの「シーダーと共有しない」を、読むだけの共有に改める）
- [Playwright, Authentication](https://playwright.dev/docs/auth)
- [Playwright, Test configuration](https://playwright.dev/docs/test-configuration)
- [Playwright, Web server](https://playwright.dev/docs/test-webserver)
- [Playwright, Retries](https://playwright.dev/docs/test-retries)
- [Playwright, Continuous Integration](https://playwright.dev/docs/ci)
- [Keycloak, Configuring the hostname (v2)](https://www.keycloak.org/server/hostname)
- [Docker, Host network driver](https://docs.docker.com/engine/network/drivers/host/)
- [spring-security#11515 のコメント](https://github.com/spring-projects/spring-security/issues/11515#issuecomment-1357510068)
