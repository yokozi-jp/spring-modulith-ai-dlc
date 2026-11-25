---
type: ADR
title: 'ADR-129: 誰が要求したかを application で確かめ、操作と操作する人の組ごとの認可の表で確かめる'
description: 誰が要求したかを application で確かめ、見える範囲は SQL で絞って 404、役割は @PreAuthorize で 403 にし、確かめ忘れは役割の判断の宣言と認可の表のテストで止める決定。
tags: [adr, backend, security, authorization, testing]
---

# ADR-129: 誰が要求したかを application で確かめ、操作と操作する人の組ごとの認可の表で確かめる

## Status

Proposed

## Date

2026-11-25

## Context

この決定の前のバックエンドの認可は、次の 3 つだけで、method security は有効でなかった。

- `SecurityConfig` の `anyRequest().authenticated()` が、業務の API を認証済みの利用者に限る。
- `TenantSelectionFilter` が、テナントを選ぶ前の業務の API を 403 にし、選んだテナントを `TenantContext` に束縛する。別のテナントの業務の行は、RLS で読めない（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
- `TenantSecurityCustomizer` が、所属の管理の API（`/api/current-tenant/members/**`）に URL の規則の `hasRole("ADMIN")` を課していた。

注文の CommandHandler は、注文を ID だけで読み（`ConfirmOrderCommandHandler`）、持ち主を確かめない。
参照業務機能のこの割り切りは [バックエンドアーキテクチャ](../backend/architecture.md) にあり、持ち主に限る認可の [#188](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/188) は、認可をどの層で確かめ、確かめ忘れをどう止めるかの決定を待っている。
役割で許す操作は、所属の管理の API（所属の一覧、役割の変更、所属の取り外し）だけである。
利用者の招待は別のシステムが担当するため（[ADR-101](ADR-101-keep-tenants-and-roles-in-app-database.md)）、招待の API はアプリに入らない。

[#315](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/315) で、`yokozi-jp/agent-software-factory`（commit `29ba897`）の ADR 0045 の認可の方針を調べ、一部を取り込むと決めた。
向こうは、方針の関数 `authorize` が返す許可の証（`Permit`）を domain の遷移の必須の引数にし、方針の呼び忘れを型で止める。
こちらでは、見える範囲を SQL で、役割を Spring Security のアノテーションで確かめるため、証の形は合わない（Alternatives Considered）。

OWASP API Security Top 10 2023 の API1（Broken Object Level Authorization）は、クライアントの入力でレコードに触るすべての関数で認可を確かめることと、認可のテストを書くことを勧める。
Spring Security のリファレンスは、アノテーションのないメソッドは守られないと書く。
アノテーションの付け忘れは、method security だけでは止まらない。

## Decision

規約の本文は [バックエンドの層の責務](../backend/layers.md#認可) に、テストの詳細は [認可の表のテスト](../backend/authorization-matrix-test.md) にある。
この ADR は、決めたことと理由だけを書く。

### 線引き

業務の規則と状態は domain で確かめる。
誰が要求したか（本人か、役割、見えるか）は application で確かめる。
domain のメソッドは、`Authentication` と `AppUserPrincipal` を受け取らない。
業務の規則が人に依るとき（申請した人と承認する人を分ける規則など）は、domain のメソッドが利用者の ID を値で受け取り、不変条件として確かめる。
人に依る規則は値の比較で足り、認証の型を domain に持ち込む理由がないためである。

### 見える範囲

見える範囲は、application が渡した利用者の ID を Repository と `<Feature>Queries` の SQL の WHERE に入れて絞り、見えなければ `NotFoundException` で 404 にする。
CommandHandler は行がなければ `NotFoundException` を投げるため、見えない行を空で返せば同じ経路で 404 になる。
一覧も SQL で絞り、読んだ後に絞らない（選択肢3）。
application は `SecurityContextHolder` を読まず、利用者を引数で受け取る。
現在時刻の `Clock` と同じく、テストで差し替えられるようにするためである。
決済記録の見える範囲は、注文の見える範囲に従う。

### 役割

役割で許す操作は、application の `@PreAuthorize` で確かめ、`SecurityConfig` の `@EnableMethodSecurity` で有効にする。
`@PreAuthorize` の値は、役割か権限の名前を渡す式に限る。
引数や Bean を参照する式を許すと、SQL の WHERE と役割の名前のほかに、SpEL の文字列の中にも認可の判断ができ、選択肢2 と同じく判断が分かれるためである。
Spring Security のリファレンスも、複雑な SpEL より権限を与える形を勧める（Favor Granting Authorities Over Complicated SpEL Expressions）。

所属の管理の API の役割も、`TenantSecurityCustomizer` の URL の規則をやめ、`ChangeMemberRoleCommandHandler`、`RemoveMemberCommandHandler`、`TenancyQueryService.findMembers` の `@PreAuthorize("hasRole('ADMIN')")` で確かめる。
役割の判断を URL の規則と application の 2 か所に分けず、application の宣言だけを見れば分かるようにするためである。

method security のアノテーションは `@PreAuthorize` だけを使う。
`@PostAuthorize` と `@PostFilter` は読んだ後に判断し、`@PreFilter` は受け取った値をメモリで絞るため使わない（選択肢3）。
`@Secured` と JSR-250 の `@RolesAllowed` は、`@EnableMethodSecurity` の既定では付けても黙って効かず、`@PreAuthorize` で足りるため有効にもしない。

検査の一覧は [バックエンドのアーキテクチャテスト](../backend/architecture-tests.md#認可) にある。
application が依存してよい method security の型は、パッケージ `org.springframework.security.access.prepost` ではなく型 `PreAuthorize` に絞る。
パッケージで許すと、同じパッケージの `@PostFilter` などが通るためである。

Listener とバッチから呼ぶメソッドに `@PreAuthorize` を付けないことは、検査せずレビューで見る。
Listener、ItemWriter、Tasklet は CommandHandler を 1 つ呼ぶだけなので、呼び出しの経路が 1 段で、差分から読めるためである。

### 役割の判断の宣言

CommandHandler と QueryService の public メソッドは、どれも役割の判断を宣言する（対象の範囲は [バックエンドの層の責務](../backend/layers.md#認可)）。
役割で許すメソッドには `@PreAuthorize` を付ける。
役割を問わないメソッドには `shared.security` の `@NoRoleRequired` を付け、役割を問わない根拠を enum の `NoRoleRequired.Basis` で渡す。
`@NoRoleRequired` は実行時に何もしない。

宣言を求めるのは、認可の表のテストの行が増えないメソッドでは、`@PreAuthorize` の付け忘れが残りうるためである。
行が増えないのは、operation を通らずに呼ぶメソッド（Listener から呼ぶ `ChargeOrderCommandHandler`）と、既存の operation の後ろに足したメソッドである。
宣言を求めれば、判断していないメソッドは ArchUnit で止まる。

根拠は、自由な文の `reason` ではなく `Basis` の定数で表す。
初めの実装の `reason` は空でないことしか検査できず、同じ文を 11 か所に写し、docs の抜粋とコードで文が食い違った。
定数なら、根拠の種類が 4 つに決まり、同じ根拠のメソッドを定数の参照で探せる。
持ち主で絞る #188 の未対応は根拠に書かず、認可の表のテストの例外の一覧で追う。

`@NoRoleRequired` はメソッドにだけ付けられる。
クラスに付ける形を許すと、後からクラスに足したメソッドも、判断しないまま役割を問わないことになるためである。
1 つのメソッドに両方を付けると、どちらが判断かを読めないため、ArchUnit で拒否する。
`@PreAuthorize` を付けたクラスのメソッドの `@NoRoleRequired` も拒否する。クラスの `@PreAuthorize` がそのメソッドにも当たり、役割を問わない宣言は効かないためである。
宣言を役割を問わないメソッドの一覧のファイルに集める形は選ばない（選択肢7）。

### 応答

見えなければ 404、見えるが許されなければ 403 にし、[HTTPステータスコードの選択](../web-api/status-codes.md) の規約は変えない。
method security の拒否で投げる `AuthorizationDeniedException` は `AccessDeniedException` の派生なので、今の `ApiExceptionHandler` のまま `about:blank` の 403 になる。

### 確かめ忘れの止め方

操作と操作する人の組ごとに期待を書いた [認可の表のテスト](../backend/authorization-matrix-test.md) で止める。

- テストは Spring と DB を起動した MockMvc の結合テストにする。見える範囲は SQL に、役割はアノテーションにあり、どちらも Spring と DB を通さないと動かないためである（選択肢6）。
- 行は commit した `openapi/openapi.yaml` の operation にし、期待の表は `default` のない `switch` 式で書く。operation を足したときに、表から漏れず、期待の書き忘れがコンパイルで止まるためである。
- 期待は本番の方針から作らない。作ると、本番の判断を同じ本番の判断と比べるだけになる。
- 判定は status に加えて、本文と業務の行も確かめる。200 で別のテナントの行を返す一覧や、404 を返しても他人の行を書き換える実装は、status だけでは見分けられないためである。

操作する人は、[#568](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/568) の 4 種類に、レビューで別のテナントの admin を足した 5 種類にする。
所属の管理の API は ADMIN だけが呼べるため、別のテナントの member では、別のテナントの利用者の ID が見えないことを確かめる前に 403 になるためである。

期待と違ってよい組は、理由と Issue の番号に加えて、今の結果を持つ例外の一覧に書く。
例外の組は、今の結果と違う形で壊れたときと、期待どおりに直ったときに失敗にする。
判定から外すと別の壊れ方を見逃し、直った後に残すと同じ組が戻っても止まらないためである。

[#188](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/188) が入るまでの、同じテナントの別の利用者が注文と決済記録に触れる組が、この一覧に載る。
Issue の #568 では、別のテナントの組も [#363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363) で載せるとしたが、載せない。
別のテナントの業務の行は RLS で読めず（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）、別のテナントの組は今も期待どおりになるためである。

### ライブラリ

Spring Security の method security だけを使う。
`spring-security-acl`、Cedar、OPA、OpenFGA、SpiceDB、Cerbos は入れない。
役割は `ROLE_ADMIN` と `ROLE_MEMBER` の 2 つ、関係は注文の持ち主の 1 種類だけで、設定と運用の費用に見合わないためである。
部署の階層のように役割が入れ子になるとき、または代理や共有のように行ごとに見える人が変わる関係が増えたときは、ライブラリと方針エンジンを入れないこの判断を見直す。

## Consequences

### Positive

- 見えない行を SQL で読まないため、一覧の件数とページングが見える行だけで決まり、他人の行がアプリのメモリに載らない。
- 操作を足したときの期待の書き忘れはコンパイルで、他人の id で読めて書ける漏れは Pull Request の CI で止まる。
- 認可の判断は SQL の WHERE と method security のアノテーションにだけあり、自作の方針の関数と二重にならない。
- 役割を判断していない CommandHandler と QueryService のメソッドは、operation の後ろにあるかを問わず ArchUnit で止まる。
- domain が認証の型に依存しないため、domain の単体テストで認証を作らなくてよい。

### Negative

- 認可の表のテストは Spring と DB を起動し、operation の数と 5 種類の操作する人の積だけ要求を送るため、テストの時間が増える。
- 見える範囲の条件は Repository と `<Feature>Queries` ごとに書く。条件の書き忘れは型では止まらず、認可の表のテストでだけ止まる。
- `@PreAuthorize` を付けたメソッドは、認証のない呼び出し（Listener、バッチ）から呼ぶと拒否される。役割で許す操作を Listener からも呼ぶ必要が出たら、CommandHandler を分ける。
- 所属の管理の API は、既定で拒否する形から、既定で許す形に変わる。前の URL の規則は、`/api/current-tenant/members/**` の配下を、足した endpoint も含めて ADMIN に限っていた。今は、足した endpoint が守られるのは、呼ぶ application のメソッドが `@PreAuthorize` を宣言したときだけである。ArchUnit の規則は宣言があることだけを確かめ、`@NoRoleRequired` を誤って選んだ宣言は通す。宣言の中身を確かめるのは、認可の表のテストの期待の表である。
- 所属の管理の API の役割の確かめが、Controller の引数の検証より後に動く method security に移る。そのため、管理者でない利用者にも、形の誤りは 403 より先に 400 で返る（[HTTPステータスコードの選択](../web-api/status-codes.md#判断に迷うユースケース)）。
- CommandHandler と QueryService を足すたびに、`@PreAuthorize` か `@NoRoleRequired` を書く手間が増える。

### Neutral

- 持ち主の表し方（作成した利用者の ID を注文に持つか）と、admin に持ち主でない注文を見せるかは、#188 で決める。#188 で、例外の一覧から #188 の組を消す。
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

### 選択肢7: 役割を問わないメソッドを 1 つのファイルの一覧に持つ

- **Description**：役割を問わないメソッドの名前を、ArchUnit のテストの側か設定のファイルの許可の一覧に並べる。一覧にも `@PreAuthorize` もないメソッドを失敗にする。
- **Pros**：アノテーションの型を足さずに済み、役割を問わないメソッドを 1 か所で見渡せる。
- **Cons**：一覧が CommandHandler から離れ、メソッドの差分を読んでも判断が見えない。メソッドを改名、削除しても一覧の行が残るため、残った行を消す検査も要る。`@NoRoleRequired` なら、判断と根拠がメソッドの宣言と同じ差分に載る。

### 選択肢8: 役割を問わない宣言に @PreAuthorize("permitAll()") を使う

- **Description**：新しいアノテーションを足さず、役割を問わないメソッドに `@PreAuthorize("permitAll()")` を付ける。
- **Pros**：アノテーションの型を足さずに済み、宣言を `@PreAuthorize` の有無だけで検査できる。
- **Cons**：`permitAll()` は役割を問わない根拠を持てず、`TENANT_SCOPED` と `CALLED_WITHOUT_AUTHENTICATION` を区別できない。`preAuthorizeUsesOnlyRoleOrAuthorityNames` にも `permitAll()` の例外が要る。
Spring Security 7.2.0 で確かめると、Authentication のない呼び出しでも `permitAll()` は拒否されない（`hasRole('ADMIN')` は `AuthenticationCredentialsNotFoundException` で拒否される）。Listener から呼べないことは、この案を採らない理由にならない。

## References

- [#568](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/568)（この ADR と実装の Issue）
- [#315](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/315) のコメントと、ブランチ `research/authorization` の `research/authorization.md`（調べた事実と出典）
- [APIの認証、セッション、権限](../web-api/authentication-and-session.md)
- [ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md)、[ADR-077](ADR-077-check-contract-test-coverage-per-response-status.md)、[ADR-101](ADR-101-keep-tenants-and-roles-in-app-database.md)、[ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)、[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- `yokozi-jp/agent-software-factory`（commit `29ba897`）の `docs/adr/0045-authorization-policy-in-application-with-permit-token.md`、`docs/domain-model/authorization.md`、`apps/api/test/bola.ts`、`apps/api/test/bola-fixtures.ts`
- Spring Security の [Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)、[Spring Data Integration](https://docs.spring.io/spring-security/reference/servlet/integrations/data.html)、[Domain Object Security (ACLs)](https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html)
- OWASP [API1:2023 Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/)、[Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
- [JLS (Java SE 25) 15.28.1](https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.28.1)（switch 式の網羅）
