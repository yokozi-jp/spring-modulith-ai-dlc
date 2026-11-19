---
type: ADR
title: 'ADR-101: テナント、所属、役割をアプリの DB に持ち、テナントを選んだ時点で役割の権限を組み立てる'
description: IdP には認証だけを任せ、テナント、所属、役割を tenancy モジュールの表に持つ決定。ログインの後にテナントを 1 つ選んだ時点でセッションの ID を作り直し、テナントと役割を持つ 1 つの権限（ROLE_ADMIN か ROLE_MEMBER）を組み立て、リクエストのたびに所属を引き直す。
tags: [adr, security, auth, tenancy, session]
---

# ADR-101: テナント、所属、役割をアプリの DB に持ち、テナントを選んだ時点で役割の権限を組み立てる

## Status

Proposed

## Date

2026-11-19

## Context

[issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369) は、業務のデータを**テナント**（サービスを契約する単位の組織）ごとに分ける。
1 人の利用者は複数のテナントに属せ、テナントごとに役割（管理者か、そうでない利用者か）を持つ。
利用者は IdP でログインし、アプリは ID トークンの `iss` と `sub` の組から利用者を引く（[ADR-096](ADR-096-require-app-user-principal-for-audit-columns.md)、#374）。
IdP は、ローカルとテストでは Keycloak、本番では Amazon Cognito である（[ADR-102](ADR-102-use-amazon-cognito-as-production-idp.md)）。

役割を IdP に持たせると、次のことが起きる。

- Keycloak のレルムの役割は、アクセストークンの `realm_access` に平らに入り、どのテナントの役割かを区別できない。
- Cognito のグループで表すと、グループ名にテナントを埋め込むことになる。グループの数には上限がある（#316 の調査）。
- Keycloak と Cognito で claim の形が違うため、2 つの読み方を保つことになる。
- 役割を変えても、利用者がログインし直すまで効かない。

テナントと所属は、テナントが決まる前に引く。
そのため、後続の RLS（#377）の外に置く必要がある。

この判断は、#363 の grilling の Q10、Q11、Q13、Q16 と、#316 の Q18 から Q21 で決めた内容を、実装の形にしたものである。
決めた時点では、Spring Security 7 で認証のトークンを作り直したときの OAuth2 の認可済みのクライアントとの対応と、セッションの ID を作り直したときの CSRF のトークンの扱いが確かめられていなかった。
どちらも #375 のテストで確かめ、結果を [APIの認証、セッション、権限](../web-api/authentication-and-session.md#テナントを選んだ後のセッション)に記録した。

## Decision

テナント、所属、役割をアプリの DB に持ち、IdP には認証だけを任せる。

- `tenancy` モジュールの `m_tenant` にテナントを持つ。主キーは bigint の IDENTITY で、外に出す ID は `public_id`（UUID v4）にする。
- `m_membership` に所属を持つ。所属は利用者、テナント、役割（`ADMIN` か `MEMBER` のどちらか 1 つ）を持つ。
- IdP の役割とグループを使わない。ローカルの Keycloak の realm からレルムの役割を削除し、管理者は seed の所属で作る。

ログインの後に、所属の数でテナントを決める。

- 所属が 1 つなら、ログインの成功の処理でそのテナントを選ぶ。
- 所属がないか 2 つ以上なら選ばない。SPA が、招待を待つ画面かテナントを選ぶ画面を出す。
- ログインの時点では役割の権限を組み立てない。

テナントを選ぶときは、次の順で書き換える。

1. セッションの ID を作り直す（`HttpServletRequest#changeSessionId`）。
2. テナントの `public_id` と所属の役割を持つ権限（`TenantMembershipAuthority`）を足した認証を作り直し、`SecurityContextRepository` でセッションに保存する。権限の名前は、役割から `ROLE_ADMIN` か `ROLE_MEMBER` にする。

テナントを選んだかどうかは、認証がこの権限を持つかどうかだけで表し、セッションの属性に別の値を持たない。
業務の API の拒否も、所属の引き直しも、選んだテナントを返す API も、この権限だけを読む。

ブラウザから来るテナントの ID は、テナントを選ぶ API（`PUT /api/current-tenant`）の本文だけにする。
この ID は所属の表で確かめてから使い、所属がなければ 422 を返す。
テナントを選んだ後にもう一度選ぶ要求も 422 にし、テナントの切り替えはログインし直して行う。
ほかのリクエストでは、テナントをリクエストの値から取らない。

リクエストのたびに、認証の利用者と権限のテナントの組で所属を引き直す。
所属が消えていれば、権限を外す。
役割が変わっていれば、新しい役割で権限を作り直す。
作り直すときはセッションの ID を変えない。
変えると、同時に送られたほかのリクエストが前の ID の Cookie で 401 になるためである。
所属の主キーではなく `(利用者の public_id, テナントの public_id)` の一意キーで引く。
所属の主キーをセッションに入れると、主キーを Repository の外に出さない [ADR-089](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md) に反するためである。

業務の API は、この権限がない認証を、type が `/problems/tenant-not-selected` の 403 で拒否する。
対象は `/api/**` のうち、所属の一覧（`GET /api/memberships`）とテナントを選ぶ API を除くすべてである。
権限不足の 403（`about:blank`）と type で区別できるようにし、SPA はこの type でテナントを選ぶ画面へ案内する。

これらの仕組みは、`tenancy` の `Customizer<HttpSecurity>` の Bean が Security filter chain に足す。
所属の引き直しと拒否は、認可の `AuthorizationFilter` の前の filter で行う。
`SecurityConfig` には `tenancy` の型を置かない。
拒否の例外（`TenantNotSelectedException`）は `shared.security` に置き、`error` が `tenancy` に依存せずに専用の type の 403 にする。
この例外はテナントの ID も役割も持たず、ログインの状態だけを表すため、`AppUserPrincipal` と同じく認証の語彙として扱う。

## Consequences

### Positive

- 所属を外すと、外された利用者の次のリクエストから拒否される。役割を変えると、次のリクエストから新しい役割の権限になる。どちらもログインし直すまで待たない。
- 選んだ状態の表し方が 1 つなので、権限はあるがテナントがない、のような食い違った状態を作れない。
- Keycloak と Cognito で、テナントと役割の扱いが同じになる。
- テナントを選んだ時点でセッションの ID が変わるため、選ぶ前のセッションの ID を固定させる攻撃が効かない。
- ログインで選ぶため、所属が 1 つの利用者は、SPA を通らないクライアント（DAST など）でも業務の API に届く。

### Negative

- テナントを選んだ後のリクエストのたびに、所属を引く問い合わせが 1 つ増える。
- 権限のクラス（`TenantMembershipAuthority`）を、認証ごと Redis のセッションに直列化する。このクラスを移動、改名するときは、デプロイと同時に既存のセッションを消す。
- `/api/**` に新しい API を足すと、テナントを選ぶ前は 403 になる。テナントの前に呼ぶ API を足すときは、`tenancy` の filter の除外を直す。

### Neutral

- 認可の判断の置き場所、403 と 404 の使い分け、役割と権限の対応は #315 で決める。
- テナントをまたいで操作する運用者は #367 で決める。

## Alternatives Considered

### 選択肢1: 役割を IdP に持つ

- **Description**：Keycloak のレルムの役割か Cognito のグループで役割を表し、ID トークンかアクセストークンの claim から権限を組み立てる。
- **Pros**：アプリに所属と役割の表が要らない。
- **Cons**：Context に挙げたとおり、テナントごとの役割を表せず、IdP ごとに読み方が分かれ、変更がログインし直すまで効かない。

### 選択肢2: リクエストごとにテナントの ID を header で送る

- **Description**：SPA がテナントの ID を独自の header で毎回送り、バックエンドが所属で確かめる。
- **Pros**：ログインしたままテナントを切り替えられる。
- **Cons**：テナントがリクエストの値から来るため、確かめ忘れた 1 つの API がそのまま別のテナントへの操作になる。#369 はテナントをサーバー側のセッションからだけ取ると決めている。

### 選択肢3: 所属が 1 つのときも SPA がテナントを選ぶ API を呼ぶ

- **Description**：バックエンドはログインで何も選ばず、SPA が所属の一覧を読み、1 つなら選ぶ API を呼ぶ。
- **Pros**：ログインの成功の処理を差し替えずに済む。
- **Cons**：ログインの後に往復が 1 つ増え、SPA を通らないクライアントはテナントを選べない。選ぶ前の画面が一瞬出る。

### 選択肢4: 業務の API の拒否を `SecurityConfig` の認可の規則に書く

- **Description**：`/api/**` に `hasAnyRole("ADMIN", "MEMBER")` を書き、テナントの前に呼ぶ API だけを `authenticated()` にする。
- **Pros**：Spring Security の標準の書き方で、規則が 1 か所に並ぶ。
- **Cons**：拒否が既定の `AuthorizationDeniedException` になり、権限不足の 403 と type で区別するには、例外の処理が認証の権限を読み直すことになる。所属の引き直しの filter とテナントの前に呼ぶ API の一覧が `tenancy` と `SecurityConfig` に分かれ、ルートのパッケージに機能の知識が入る。

### 選択肢5: 所属が消えたらセッションを破棄して 401 にする

- **Description**：所属の引き直しで所属が消えていれば、セッションを破棄し、ログインからやり直させる。
- **Pros**：テナントと役割の権限を外す処理が要らない。
- **Cons**：IdP の SSO セッションが残るため、ログインの往復の後に同じ利用者のまま戻るだけになる。SPA の 401 の遷移は 30 秒以内の 2 回目を止めるため、続けて起きると画面がエラーで止まる。

### 選択肢6: 選んだテナントの ID をセッションの属性に、役割を権限に分けて持つ

- **Description**：テナントの ID をセッションの属性に入れ、役割を `SimpleGrantedAuthority` の `ROLE_ADMIN` か `ROLE_MEMBER` にする。
- **Pros**：Spring Security の標準の権限の型だけで済み、独自のクラスをセッションに直列化しない。
- **Cons**：選んだ状態の正本が 2 つになり、役割の権限だけを持つ状態を作れる。業務の API の拒否が役割を、所属の引き直しがテナントの ID を読むと、その状態の認証は引き直しを通らずに業務の API に届く。

## References

- [issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [issue #375](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/375)
- [issue #316](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/316)
- [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363)
- [ADR-007: セッションベース認証と OIDC Authorization Code + PKCE](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-058: problem type にパスを全部書いた相対 URI を使う](ADR-058-use-path-absolute-relative-uri-for-problem-types.md)
- [ADR-089: 主キーをモジュールのスキーマと Repository の中に閉じ、外に出る値には例外なく public_id を使う](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)
- [ADR-096: 共通カラムの作成者と更新者には、AppUserPrincipal の利用者の ID だけを登録する](ADR-096-require-app-user-principal-for-audit-columns.md)
- [ADR-102: 本番の IdP に Amazon Cognito を使い、ローカルとテストでは Keycloak で代える](ADR-102-use-amazon-cognito-as-production-idp.md)
- [APIの認証、セッション、権限](../web-api/authentication-and-session.md)
- [Spring Security: Session Management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
