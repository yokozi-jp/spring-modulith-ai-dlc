---
type: Convention
title: APIの認証、セッション、権限
description: ブラウザとシステム間連携のAPI認証、ログインの後のテナントの選択、セッションの無操作と最長の期間、ログアウト、セッションとCSRFのCookieの属性、ロールの管理場所を定める規約。APIの認証方式、セッション設定、CSRFのCookieを変更するとき、テナントを選ぶ前に呼ぶAPIを足すとき、対向システムへAPIを公開するとき、権限の判定を実装するときに読む。
tags: [convention, web-api, security, auth, future-arch-guidelines]
---

# APIの認証、セッション、権限

ブラウザからのAPIは、サーバー側セッションのCookieで認証する。
ログインした利用者は所属するテナントを1つ選んでから業務のAPIを使い、役割の権限はテナントを選んだ時点で所属から組み立てる。
システム間連携でAPIを公開するときは、OAuth 2.0のClient Credentials Flowで発行したアクセストークンで認証し、可能なら送信元のネットワークも制限する。
テナント、所属、役割はIdPではなくアプリケーションのDBで管理する。

## ブラウザからの認証

ブラウザからの認証は[ADR-007](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)に従い、OIDCのAuthorization Code Flow with PKCEでログインし、以降はセッションIDのCookieで認証する。
アクセストークンはブラウザに渡さない。

未認証のAPIリクエストには、401のProblem Detailsに`WWW-Authenticate: Session realm="demo"`を付けて返す。
SPAは401を受けると、いまの画面のpath、query、hashを戻り先としてsessionStorageに保存してからログインへ遷移する。
バックエンドは、ログインの後は常に`/`へ戻す。SPAが`/`で保存した戻り先を読み、同一オリジンのpathのときだけその画面へ移す。
画面のURLを知るのはSPAだけなので、バックエンドは、401にした要求をログインの後の戻り先として保存しない（`NullRequestCache`）。
Spring Securityの既定の保存は、`Accept`のないAPIのGETを保存し、ログインの後にそのJSONへ戻すためである。
`Session`は登録されていない独自のschemeであり、選んだ理由は[ADR-059](../adr/ADR-059-send-a-session-challenge-in-www-authenticate-on-401.md)にある。

## ログインの後の利用者

ログインでは、IDトークンの`iss`と`sub`の組から、`tenancy`モジュールがアプリの利用者を引く。
組に対応づけた利用者がなければ、利用者を作って対応づける。
IdPを移るときは、組と利用者の対応づけだけを書き換え、利用者のIDは変えない。

セッションに保存する`OidcUser`の名前（`getName()`）は、IdPの`sub`ではなくアプリの利用者のIDである。
セッションの`OidcUser`は`shared.security`の`AppUserPrincipal`も実装し、共通カラムの`*_by`は`userId()`の値を登録する（[PostgreSQLの共通カラム](../database/postgresql-common-columns.md)）。
`tenancy`の`SignInOidcUserService`が`OAuth2UserService<OidcUserRequest, OidcUser>`を実装し、Spring SecurityがこのBeanをOIDCログインに使う。
ログインのコールバックでは前のセッションの認証がまだ残っているため、利用者を作る間だけ認証を外し、`*_by`には処理の名前を登録する。

セッションはPostgreSQLの`modulith.spring_session`と`modulith.spring_session_attributes`に、`OidcUser`の実装のクラス名ごと直列化して保存する（[ADR-111](../adr/ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md)）。
次の2つのときは、デプロイで既存のセッションを消す（`DELETE FROM modulith.spring_session`を実行する。`spring_session_attributes`の行は外部キーのカスケードで消える）。
すべてのインスタンスが同じ表を使うため、消すのは前の版のインスタンスがすべて止まった後にする。
前の版が動いている間に消しても、前の版が前のクラスでセッションを書き直す。

- `AppUserPrincipal`でない`OidcUser`を保存していた版から上げるとき。消さないと、そのセッションが切れるまで書き込みが失敗する。
- `SignInOidcUserService`が返す`OidcUser`のクラスか、選んだテナントの権限のクラス（`TenantMembershipAuthority`）を持たない版へ切り戻すとき、またはそれらのクラスを移動、改名するとき。消さないと、セッションを読めずにリクエストが500になる。

## テナントを選ぶ

ログインした利用者は、所属するテナントを1つ選んでから業務のAPIを使う。
テナント、所属、役割は`tenancy`の`m_tenant`と`m_membership`に持ち、IdPには持たせない。
理由は[ADR-101](../adr/ADR-101-keep-tenants-and-roles-in-app-database.md)にある。

- ログインの成功の処理（`TenantSelectionSuccessHandler`）は、所属が1つならそのテナントを選ぶ。所属がないか2つ以上なら選ばない。
- SPAは`GET /api/v1/memberships`で所属の一覧を読み、`PUT /api/v1/current-tenant`の本文でテナントのIDを送って選ぶ。所属がなければ、所属を待つ画面を出す。
- バックエンドは、送られたテナントのIDを所属の表で確かめる。所属にないテナントと、テナントを選んだ後にもう一度選ぶ要求は422にする。テナントを切り替えるには、ログインし直す。
- テナントを選ぶと、セッションのIDを作り直してから、テナントの`public_id`と所属の役割を持つ権限（`TenantMembershipAuthority`）を足した認証をセッションに保存する。権限の名前は、役割から`ROLE_ADMIN`か`ROLE_MEMBER`にする。
- テナントを選んだかどうかは、認証がこの権限を持つかどうかだけで表し、セッションの属性に別の値を持たない。書き換えは`TenantSession`だけが行う。
- リクエストのたびに、`TenantSelectionFilter`が認証の利用者と権限のテナントの組で所属を引き直す。所属が消えていれば権限を外し、役割が変わっていれば新しい役割で権限を作り直す。作り直すときはセッションのIDを変えない。変えると、同時に送られたほかのリクエストが前のIDのCookieで401になるためである。
- テナントを選ぶ前の業務のAPIは、typeが`/problems/tenant-not-selected`の403になる。所属の一覧とテナントを選ぶAPIだけは、選ぶ前にも呼べる。
- テナントのIDは、テナントを選ぶAPIの本文からだけ受け取る。ほかのAPIは、テナントをリクエストの値から取らない。
- テナントを選んだ後のリクエストは、`TenantSelectionFilter`が所属を引き直した後、リクエストの残りの処理の間だけ、選んだテナントを`shared.tenancy`の`TenantContext`に束縛する。CommandHandlerが発行するイベントは、このテナントを運ぶ（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
  `OncePerRequestFilter`は非同期ディスパッチでは動かないため、`Callable`や`DeferredResult`を返すControllerでは、非同期の処理の中でテナントが束縛されない。そのようなControllerを足すときは、束縛の方法を先に決める。

`/api/**`に、テナントを選ぶ前に呼ぶAPIを足すときは、`TenantSelectionFilter`の除外に加える。
`tenancy`の`TenantSecurityCustomizer`が、このfilterとログインの成功の処理をSecurity filter chainに足すため、`SecurityConfig`には現れない。
`TenantSecurityCustomizer`は`Customizer<HttpSecurity>`のBeanなので、Spring Securityはアプリが作るすべての`HttpSecurity`に適用する。
システム間連携のClient Credentialsのchainのように、ブラウザのセッションを使わないchainを足すときは、そのchainをこの処理から外す。
外さないと、そのchainの業務のAPIも、テナントを選んでいない認証として403になる。

`TenantSession`は、chainと同じ構成（リクエストの属性とセッション）の`SecurityContextRepository`を自分で作る。
chainの`SecurityContextRepository`は`HttpSecurity`のbuildで作られ、`TenantSecurityCustomizer`が呼ばれる時点ではまだないためである。
`SecurityConfig`でchainの`SecurityContextRepository`を差し替えるときは、`TenantSession`も同じものに合わせる。

### テナントを選んだ後のセッション

テナントを選ぶ処理が認証とセッションのIDを作り直したときの、ほかの状態への影響を`TenantSelectionTest`で確かめた。
テストは、本物の`OAuth2LoginAuthenticationFilter`のログインのコールバックとDBのセッションを通し、IdPのトークンのendpointだけを差し替える。
確かめた版は、Spring Boot 4.1.2、Spring Security 7.1.2、Spring Session 4.1.1である。

- **OAuth2の認可済みのクライアント**：ログインが保存したクライアントを、作り直した認証でも引ける。Spring Bootが構成する`AuthenticatedPrincipalOAuth2AuthorizedClientRepository`は、クライアントを登録のIDと認証の名前（`getName()`、アプリの利用者のID）で引き、作り直した認証は同じprincipalを持つためである。
- **CSRFのトークン**：セッションのIDを作り直しても`__Host-XSRF-TOKEN`のCookieは変わらず、選ぶ前に受け取ったトークンで更新できる。トークンはセッションではなくCookieにあり、テナントを選ぶ処理はログインの`CsrfAuthenticationStrategy`を通らないためである。
- **前のセッションのID**：DBから消え、そのCookieでの要求は401になる。

認可済みのクライアントの保存先は、既定の`InMemoryOAuth2AuthorizedClientService`で、DBのセッションには入らない。
アプリを再起動したときと別のインスタンスでは引けない。
今の`backend/src/main`には認可済みのクライアントを読むコードがないため、影響はない。
ログアウトでトークンを失効させるなど、認可済みのクライアントを読む処理を足すときは、保存先をセッションかDBに移す。

## セッションの期間

セッションは、無操作の30分と、作成からの8時間のどちらか早いほうで切れる。

- 無操作の期間は、[application.yaml](../../backend/src/main/resources/application.yaml)の`server.servlet.session.timeout`（`30m`）に書く。`spring.session.timeout`は書かない。
- 最長の期間は、`app.session.max-lifetime`（`8h`）に書く。
- 期間を過ぎたセッションのリクエストは未認証になり、401になる（[ブラウザからの認証](#ブラウザからの認証)）。

最長の期間を切る仕組みと、IdPのSSOセッションが残るときの扱いは[ADR-135](../adr/ADR-135-add-absolute-session-lifetime-and-explicit-idle-timeout.md)にある。

## ログアウト

ログアウトでは、ADR-007に従ってアプリケーションのセッションを破棄し、IdPのSSOセッションを終了する。
IdPがトークンの失効エンドポイント（RFC 7009）を提供する場合は、セッションに保持したトークンを失効させてからセッションを破棄する。
IdPはログアウト後に、利用者をSPAの`/logged-out`へ戻す。
本番のIdPには`https://<origin>/logged-out`を、post logout redirect URI（Cognitoではsign-out URL）として登録する。

## Cookie

Cookieは、セッションIDとADR-007のCSRFトークン以外の用途に使わない。
どちらのCookieも次のとおりにする。

- Cookieの値にユーザーIDや権限などの情報を含めない。
- 名前を`__Host-`で始める。ブラウザが`Secure`、`Path=/`、`Domain`なしを強制し、別のhostから書き込めなくなる。`Secure`は環境によらず付ける。
- `SameSite`は`Lax`にする。

### セッションCookie

セッションCookieは`__Host-APP_SESSION`にし、`HttpOnly`を付ける。
`Strict`にしないのは、別siteのIdPからのcallbackでセッションに保存した認可リクエストを失うためである（[ADR-119](../adr/ADR-119-harden-session-cookie-with-host-prefix.md)）。
設定は[application.yaml](../../backend/src/main/resources/application.yaml)にあり、そのほかの扱いは次のとおりにする。

- セッションIDは、フレームワークが暗号論的に安全な乱数で生成した、十分に長く推測困難な値だけにする。
- `Max-Age`と`Expires`を付けず、ブラウザのセッションのCookieにする。期間はサーバーが切る（[セッションの期間](#セッションの期間)）。
- ログインのたびにセッションIDを再生成し、ログイン前の値を引き継がない。
- ログアウトでは、サーバーのセッションを破棄し、Cookieを`Max-Age=0`で削除する。

### CSRFのCookie

`__Host-XSRF-TOKEN`にし、SPAが読むため`HttpOnly`を付けない。
有効期間は付けず、発行と削除はSpring Securityに任せる。
CSRFトークンの検証に失敗した403は、権限不足の403と区別できるよう`/problems/csrf-token-invalid`で返す。
対象は、`/api/**`の更新系と、`/logout`のフォームの送信である。
`SecurityConfig`が登録する例外処理のhandlerは1つだけで、Spring Securityはhandlerが1つのとき対象のpathを見ずにすべての拒否へ使うため、`/logout`も同じadviceを通る。
SPAは、`apiFetch`を通る更新でこの403を受けたときだけ、Cookieを読み直して1回だけ再送する。
再送が効くのは、CSRFのCookieがない場合と、ログインへの遷移などでトークンが入れ替わった競合である。
Cookieはあるが`X-XSRF-TOKEN` headerと食い違う場合は、403がCookieを作り直さないので、再送しても同じ403になる。
`/logout`はフォームの送信なので再送しない。
設定は[SecurityConfig](../../backend/src/main/java/com/example/demo/SecurityConfig.java)、理由は[ADR-066](../adr/ADR-066-harden-csrf-cookie-with-host-prefix.md)にある。

### 別のオリジンからの更新の拒否

CSRFは、`Origin`と`Sec-Fetch-Site`の検査と、CSRFのトークンの照合の2つの層で防ぐ。
`/api/**`と`/logout`へのGET、HEAD、OPTIONS以外の要求で、次のどちらかに当てはまれば、CSRFのトークンを確かめる前に`/problems/cross-origin-request-rejected`の403で返す。

- `Origin`があり、`CorsUtils.isCorsRequest`が別のオリジンと判定する。
  `Origin: null`と解釈できない値も拒否する。
  オリジンは、X-Forwarded-*を反映した公開のscheme、host、portと比べる。
- `Sec-Fetch-Site`があり、値が`same-origin`と`none`のどちらでもない。

どちらのheaderもない要求は通し、CSRFのトークンの照合に任せる。
SPAはこの403を再送しない。
検査は[CrossOriginRequestFilter](../../backend/src/main/java/com/example/demo/CrossOriginRequestFilter.java)、理由と配信の前提は[ADR-136](../adr/ADR-136-reject-cross-origin-updates-by-origin-and-fetch-metadata.md)にある。

## システム間連携の認証

対向システムへAPIを公開する場合も、認証のないAPIは作らない。

- 認証は、OAuth 2.0のClient Credentials Flowで認可サーバーから取得したアクセストークンを`Authorization`ヘッダーで受け取る方式にする。
- 対向システムが認可サーバーへ接続できない場合に限り、APIアクセスキーを検討する。
- 可能であれば、送信元IPでネットワークの到達範囲も制限する。

ADR-007は未認証で許可する経路をヘルスチェックに限り、セッション以外の認証方式を定めていない。
システム間連携の認証を初めて実装するときは、ADRを起こす。

同じアプリケーション内のバッチ処理は、自分のWeb APIを呼ばず、アプリケーション層を直接呼ぶ。

## ロールの管理

IdPはユーザーの認証だけを担い、テナントの中の役割は所属の行に持つ（[テナントを選ぶ](#テナントを選ぶ)）。
IdPのレルムの役割とグループは使わない。
ローカルのKeycloakのrealm（`docker/keycloak/realm.json`）にもレルムの役割を置かず、管理者の権限は開発用の代表データの所属で作る。
本番のIdPはAmazon Cognitoで、カスタム属性とグループを使わない（[ADR-102](../adr/ADR-102-use-amazon-cognito-as-production-idp.md)）。

権限がない場合のステータスコードは[HTTPステータスコードの選択](status-codes.md)に従う。

## テナントの所属の管理

選んだテナントの所属の一覧、役割の変更、所属の取り外しは、`/api/v1/current-tenant/members`の配下にあり、テナントの管理者だけが呼べる。
`tenancy`の`TenantSecurityCustomizer`が、このパスに`hasRole("ADMIN")`を課す。
`ROLE_ADMIN`は`TenantSelectionFilter`が引き直した所属の役割から来るため、役割の変更と取り外しは、その利用者の次のリクエストから効く。
管理者でない利用者には、`about:blank`の403を返す。

対象のテナントは、パスにもリクエストの値にも持たず、セッションで選んだテナントから取る。
そのため、別のテナントの所属は一覧に出ず、パスに別のテナントだけに所属する利用者のIDを指定しても404になる。

各テナントに管理者を1人以上残す規則は、`tenancy`の`Tenant`集約が持つ。
最後の管理者の役割を下げる操作と、最後の管理者を外す操作は、422になる。
所属の変更はテナントの`lock_no`で競合を検出するため、一覧の応答の`lockNo`を、役割の変更の本文と所属を外すクエリパラメータで送り返す（[更新の競合制御](optimistic-locking.md)）。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
