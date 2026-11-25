---
type: ADR
title: 'ADR-129: 誰が要求したかを application で確かめ、操作と操作する人の組ごとの認可の表で確かめる'
description: 業務の規則と状態は domain、誰が要求したか（本人か、役割、見えるか）は application で確かめる決定。見える範囲は Repository の SQL で絞って見えなければ 404、役割は CommandHandler の @PreAuthorize で確かめて 403 にし、確かめ忘れは OpenAPI の operation と操作する人の組ごとの認可の表のテストで止め、認可のライブラリと方針エンジンは入れない。
tags: [adr, backend, security, authorization, testing]
---

# ADR-129: 誰が要求したかを application で確かめ、操作と操作する人の組ごとの認可の表で確かめる

## Status

Proposed

## Date

2026-11-25

## Context

今のバックエンドの認可は、次の 3 つだけである。

- `SecurityConfig` の `anyRequest().authenticated()` が、業務の API を認証済みの利用者に限る。
- `TenantSelectionFilter` が、テナントを選ぶ前の業務の API を 403 にし、選んだテナントを `TenantContext` に束縛する。別のテナントの業務の行は、RLS で読めない（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
- `TenantSecurityCustomizer` が、所属の管理の API（`/api/current-tenant/members/**`）に URL の規則の `hasRole("ADMIN")` を課す。

`backend/src/main` に `@EnableMethodSecurity` と `@PreAuthorize` はない。
注文の CommandHandler は、注文を ID だけで読み（`ConfirmOrderCommandHandler`）、持ち主を確かめない。
参照業務機能のこの割り切りは [バックエンドアーキテクチャ](../backend/architecture.md) に書いてあり、持ち主に限る認可は [#188](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/188) で扱う。
持ち主に限る認可の #188 と、管理者の招待の [#380](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/380) は、認可をどの層で確かめるか、役割をどう確かめるか、確かめ忘れをどう止めるかの決定を待っている。

[#315](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/315) で、`yokozi-jp/agent-software-factory`（commit `29ba897`）の ADR 0045 の認可の方針を調べ、一部を取り込むと決めた。
向こうは、方針の関数 `authorize` が返す許可の証（`Permit`）を domain の遷移の必須の引数にし、方針の呼び忘れを型で止める。
こちらでは、見える範囲を SQL で、役割を Spring Security のアノテーションで確かめるため、証の形は合わない（Alternatives Considered）。

OWASP API Security Top 10 2023 の API1（Broken Object Level Authorization）は、クライアントの入力でレコードに触るすべての関数で認可を確かめることと、認可のテストを書くことを勧める。
Spring Security のリファレンスは、アノテーションのないメソッドは守られないと書く。
アノテーションの付け忘れは、method security だけでは止まらない。

## Decision

### 線引き

業務の規則と状態は domain で確かめる。
誰が要求したか（本人か、役割、見えるか）は application で確かめる。
domain のメソッドは、`Authentication` と `AppUserPrincipal` を受け取らない。
業務の規則が人に依るとき（申請した人と承認する人を分ける規則など）は、domain のメソッドが利用者の ID を値で受け取り、不変条件として確かめる。

### 見える範囲

Controller は、`@AuthenticationPrincipal` で受け取った `AppUserPrincipal` の `userId()` を、Command と検索条件に入れる。
CommandHandler と QueryService は、その ID を Repository と `<Feature>Queries` に渡す。
Repository は、その ID を jOOQ の WHERE に入れ、見えない行を空で返す。
CommandHandler と QueryService は、空なら `NotFoundException` を投げ、`ApiExceptionHandler` が 404 にする。
一覧も同じ条件で SQL で絞り、`@PostFilter` と `@PostAuthorize` は使わない。
決済記録の見える範囲は、注文の見える範囲に従う。
application は `SecurityContextHolder` を読まない。
現在時刻の `Clock` と同じく、利用者を引数で受け取り、テストで差し替えられるようにする。

### 役割

役割で許す操作は、CommandHandler の `handle`（参照なら QueryService の public メソッド）に、`@PreAuthorize("hasRole('ADMIN')")` のように役割か権限の名前だけを書く。
`SecurityConfig` に `@EnableMethodSecurity` を付けて有効にする。
引数や Bean を参照する SpEL は書かない。
method security のアノテーション（`@PreAuthorize`、`@PostAuthorize`、`@PreFilter`、`@PostFilter`、`@Secured`）は、各モジュールの `application` のクラスだけに付け、ArchUnit の規則で検査する。

### 応答

見えなければ 404、見えるが許されなければ 403 にする。
これは [HTTPステータスコードの選択](../web-api/status-codes.md) の今の規約と同じで、規約は変えない。
method security の拒否で投げる `AuthorizationDeniedException` は `AccessDeniedException` の派生なので、`ApiExceptionHandler` が `about:blank` の 403 にする。

### 確かめ忘れの止め方

操作と操作する人の組ごとに期待を書いた認可の表のテストで止める。
テストは Spring と DB を起動した MockMvc の結合テストにし、行を commit した `openapi/openapi.yaml` の operation、列を操作する人の種類にする。
操作する人は、持ち主、同じテナントの別の member、同じテナントの admin で持ち主でない人、別のテナントの member の 4 種類にする。

- テストの側の操作の enum と spec の operation が一致することを、`ApiContractCoverageTest` と同じく swagger-core で spec を読んで確かめる。
- 期待の表は、操作の enum の `switch` 式で書き、`default` を書かない。操作を足すと、期待の足し忘れがコンパイルで止まる。
- 期待は本番の方針から作らない。作ると、本番の判断を同じ本番の判断と比べるだけになる。
- 拒否を期待する組では、id を持つ操作の応答が、存在しない id で叩いたときと同じ status と本文になることを確かめる。id を持つ操作は、パスに id を持つ操作と、名前が `id` か `Id` で終わる query の引数を持つ操作で、Spectral の `public-id-uuid` と同じ選び方にする。query を見ないと、注文の id を `orderId` で受け取る `GET /api/payments` が抜ける。
- 拒否を期待する書き込みの組では、業務のスキーマの行の中身が変わらないことも確かめる。
- 期待と違ってよい組は、理由と Issue の番号付きの例外の一覧に書く。理由が空の例外と、spec にない操作の例外は失敗にする。#188 が入るまでの、同じテナントの別の利用者が注文と決済記録に触れる組が、この一覧に載る。
- 判定の部分を、テストの側の fixture の Controller（持ち主で絞らない GET と、404 を返すが他人の行を書き換える PUT）でも動かし、違反を検出することを確かめる。

### ライブラリ

Spring Security の method security だけを使う。
`spring-security-acl`、Cedar、OPA、OpenFGA、SpiceDB、Cerbos は入れない。
役割は `ROLE_ADMIN` と `ROLE_MEMBER` の 2 つ、関係は注文の持ち主の 1 種類だけで、設定と運用の費用に見合わないためである。

### 見直す条件

部署の階層のように役割が入れ子になるとき、または代理や共有のように行ごとに見える人が変わる関係が増えたときは、ライブラリと方針エンジンを入れないこの判断を見直す。

## Consequences

### Positive

- 見えない行を SQL で読まないため、一覧の件数とページングが見える行だけで決まり、他人の行がアプリのメモリに載らない。
- 操作を足したときの期待の書き忘れはコンパイルで、他人の id で読めて書ける漏れは Pull Request の CI で止まる。
- 認可の判断は SQL の WHERE と method security のアノテーションにだけあり、自作の方針の関数と二重にならない。
- domain が認証の型に依存しないため、domain の単体テストで認証を作らなくてよい。

### Negative

- 認可の表のテストは Spring と DB を起動し、operation の数と 4 種類の操作する人の積だけ要求を送るため、テストの時間が増える。
- 見える範囲の条件は Repository と `<Feature>Queries` ごとに書く。条件の書き忘れは型では止まらず、認可の表のテストでだけ止まる。
- `@PreAuthorize` を付けたメソッドは、認証のない呼び出し（Listener、バッチ）から呼ぶと拒否される。役割で許す操作を Listener からも呼ぶ必要が出たら、CommandHandler を分ける。

### Neutral

- 所属の管理の API は、この ADR の前から `TenantSecurityCustomizer` の URL の規則で役割を確かめている。認可の表のテストは、どちらの仕組みで拒否しても 403 を確かめる。
- 持ち主の表し方（作成した利用者の ID を注文に持つか）と、admin に持ち主でない注文を見せるかは、#188 で決める。#188 で、例外の一覧から #188 の組を消す。
- #380 は、管理者だけの操作に `@PreAuthorize("hasRole('ADMIN')")` を付け、認可の表に行を足す。
- [ADR-126](ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md) の Idempotency-Key が入ったら、認可の表のテストの書き込みの要求にキーを付ける。
- 拒否のログは足さない。ログは利用者の ID を `user.id` で持ち、拒否の急増の検知は可観測性の送り先で扱う。

## Alternatives Considered

### 選択肢1: 許可の証（Permit）を domain の遷移の引数に取る

- **Description**：向こうの ADR 0045 の形。application の方針の関数が返す証を、domain の遷移の必須の引数にする。
- **Pros**：遷移を呼ぶ前に方針を通ったことを、型で求められる。
- **Cons**：Java の型だけでは、証を作る場所を方針に限れない。証の型を domain に置くと application から作るためにコンストラクタを公開する必要があり、application に置くと domain が application に依存して [ADR-002](ADR-002-package-by-feature-onion-architecture.md) の依存の向きに反する。作る場所を限るには ArchUnit の規則が要り、向こうにもその検査はまだない。証が守るのは domain の遷移だけで、BOLA の主な形である一覧と詳細の読み取りは守らない。Listener から呼ぶ遷移（`OrderConfirmedListener` が呼ぶ `ChargeOrderCommandHandler`）には要求した人がいないため、人のいない証を作る抜け道が要る。こちらの domain には人に依る業務の規則がまだなく、入っても利用者の ID を値で渡せば足りる。

### 選択肢2: 自作の方針の関数 authorize(actor, 操作, 対象)

- **Description**：向こうの `authorize` のように、誰が、どの対象に、何をできるかを 1 つの関数で判断する。
- **Pros**：判断が 1 か所に集まり、単体テストで網羅できる。
- **Cons**：見える範囲は SQL の WHERE に、役割は method security にある。同じ判断を自作の関数にも持つと、判断が 2 か所に分かれる。

### 選択肢3: @PostFilter と @PostAuthorize で読んだ後に絞る

- **Description**：Repository は利用者の条件なしに読み、戻り値を Spring Security のアノテーションで絞るか拒否する。
- **Pros**：Repository と `<Feature>Queries` に利用者の条件を書かなくてよい。
- **Cons**：他人の行を DB から読み、メモリで捨てる。Spring Security のリファレンスは、メモリでの絞り込みは高くつくのでデータの層で絞ることを検討するよう書き、ページングには問い合わせに利用者を入れる必要があると書く。

### 選択肢4: spring-security-acl

- **Description**：ドメインオブジェクトごとに ACL の行を持ち、利用者ごとの許可を引く。
- **Pros**：利用者ごとに共有先が変わる要件に合う。
- **Cons**：ACL の表と、その行を書く処理と運用が要る。こちらの関係は注文の持ち主の 1 種類で、持ち主の条件を WHERE に入れれば足りる。

### 選択肢5: 方針エンジンか認可のサービス（Cedar、OPA、OpenFGA、SpiceDB、Cerbos）

- **Description**：方針を専用の言語かサービスで書き、アプリから問い合わせる。
- **Pros**：部署の階層や代理のような関係と、方針の変更履歴の管理に向く。
- **Cons**：役割が 2 つで関係が 1 種類の今は、方針の言語の習得、サービスの運用、関係のデータの同期の費用に見合わない。

### 選択肢6: 方針の単体テストと遷移の単体テストの 2 本立て

- **Description**：向こうの網羅のテストのように、方針の関数と domain の遷移を単体テストで網羅し、BOLA は別の結合テストで確かめる。
- **Pros**：Spring と DB を起動しないため、テストが速い。
- **Cons**：こちらの判断は SQL の WHERE とアノテーションにあり、単体テストでは動かない。API の結合テスト 1 つ（認可の表のテスト）にまとめる。

## References

- [#568](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/568)（この ADR と実装の Issue）
- [#315](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/315) のコメントと、ブランチ `research/authorization` の `research/authorization.md`（調べた事実と出典）
- [バックエンドの層の責務](../backend/layers.md)
- [APIの認証、セッション、権限](../web-api/authentication-and-session.md)
- [バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)
- [HTTPステータスコードの選択](../web-api/status-codes.md)
- [ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md)、[ADR-077](ADR-077-check-contract-test-coverage-per-response-status.md)、[ADR-101](ADR-101-keep-tenants-and-roles-in-app-database.md)、[ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)、[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- `yokozi-jp/agent-software-factory`（commit `29ba897`）の `docs/adr/0045-authorization-policy-in-application-with-permit-token.md`、`docs/domain-model/authorization.md`、`apps/api/test/bola.ts`、`apps/api/test/bola-fixtures.ts`
- Spring Security の [Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)、[Spring Data Integration](https://docs.spring.io/spring-security/reference/servlet/integrations/data.html)、[Domain Object Security (ACLs)](https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html)
- OWASP [API1:2023 Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)、[Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
- [JLS (Java SE 25) 15.28.1](https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.28.1)（switch 式の網羅）
