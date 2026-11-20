# 認可の方針と、BOLA と組み合わせの網羅のテスト（#315 の調査）

この文書は #315 の調査の記録で、merge しない。
結論は #315 のコメントと同じで、作業は「作業の Issue」の節の Issue で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `63e5c9f`）である。

## 結論

一部採用とする。
向こうの ADR 0045 から、次を取り込む。

- 業務の規則と状態は domain、誰が要求したか（本人か、役割、見えるか）は application で確かめる線引き。
- 見えないものは読まず、404 で存在を隠す。見えるが許されない操作は 403 にする。
- 操作と操作する人の組ごとの期待の表を、本番の方針とは別にテストの側に書き、操作の足し忘れを型で止める。
- 他人のリソースの id で読めず書けないことを、commit した OpenAPI の契約の全操作について PR の CI で確かめ、例外は理由付きの一覧にし、わざと漏らす fixture で検出力を確かめる。
- 認可のライブラリと方針エンジンを入れない。

こちらでの形は次のとおりにする。

- 見えるかは、読むときの SQL の条件で絞る。application が要求した利用者の ID を Repository と `<Feature>Queries` に渡し、見えなければ空を返して `NotFoundException`（404）にする。
- 役割による操作の許可は、Spring Security の method security（`@EnableMethodSecurity` と、CommandHandler の `@PreAuthorize("hasRole('ADMIN')")`）で確かめる。拒否は 403 になる。
- 組み合わせの網羅と BOLA は、1 つの API の結合テスト（認可の表のテスト）にする。行は契約の operation、列は操作する人の種類で、期待の表は enum の `switch` 式で書く。

許可の証（`Permit`）を domain の遷移の引数に取る形と、自作の方針の関数 `authorize` は取り込まない。
既存の ADR とぶつかるものはない。
作業の Issue #568 を起こし、#188 と #380 に前提をコメントで足す。

## 取り込むもの

- **domain と application の線引き**（ADR 0045 の「domain と application の線引き」）：domain のメソッドは、認証の型（`Authentication`、`AppUserPrincipal`）を受け取らない。
  身元、役割、持ち主に依る確認は application で行う。
  業務の規則が人に依るとき（向こうの職務分掌のような規則）は、domain が利用者の ID を値で受け取り、不変条件として確かめる。
  今のこちらの domain は、利用者を受け取るメソッドを持たない（`Order.confirm()` は引数がない。`ConfirmOrderCommandHandler.java:46-47`）。
- **見えるかは読むときに絞り、見えなければ 404**：向こうは「見えるものだけを読む（見えなければ 404）」とする（ADR 0045 の「domain と application の線引き」）。
  こちらでは、Controller が `@AuthenticationPrincipal` で受け取った `AppUserPrincipal` の `userId()` を Command と検索条件に入れ、CommandHandler と QueryService が Repository と `<Feature>Queries` に渡す。
  Repository は、その条件を jOOQ の WHERE に入れる。
  利用者を `SecurityContextHolder` から application で読まず、引数で渡す。現在時刻を `Clock` で渡すのと同じく、テストで差し替えられるようにするためである（向こうも `actor` を引数で渡す）。
  今の CommandHandler は、注文がなければ `NotFoundException` を投げ（`ConfirmOrderCommandHandler.java:41-45`）、`ApiExceptionHandler` が 404 にする（`ApiExceptionHandler.java:103-107`）。見えない注文を空で返せば、同じ経路で 404 になる。
  一覧も同じ条件で SQL で絞る。`@PostFilter` と `@PostAuthorize` で読んだ後に絞る形は採らない。Spring Security のリファレンスは、メモリでの絞り込みは高くつくのでデータの層で絞ることを検討するよう書き（`method-security.adoc` の `@PostFilter` の節）、Spring Data の統合の章は、ページングのためには問い合わせに利用者を入れることが必要だと書く（`data.adoc:4`）。
  決済記録の見える範囲は、注文の見える範囲に従う。`payment` は `ordering` の公開の `OrderQueries` で、注文がその利用者に見えるかを確かめる。持ち主の表し方と、管理者に全件を見せるかは #188 で決める。
- **役割による操作の許可は Spring Security の method security**：役割で許す操作（テナントの管理者だけの招待など）は、CommandHandler の `handle` に `@PreAuthorize("hasRole('ADMIN')")` を付ける。
  `@EnableMethodSecurity` を `SecurityConfig` に付けて有効にする。今の main には付いていない。
  SpEL は役割か権限の名前だけにし、複雑な式を書かない。リファレンスが「Favor Granting Authorities Over Complicated SpEL Expressions」で勧める形である（`method-security.adoc:206`）。
  拒否で投げる `AuthorizationDeniedException` は `AccessDeniedException` の派生（`AuthorizationDeniedException.java:30`）で、`ApiExceptionHandler.handleAccessDeniedException` が 403 の `about:blank` にする（`ApiExceptionHandler.java:64-80`）。
  `ProxyRulesArchTest` は `@PreAuthorize` と `@Secured` をすでにプロキシで動くアノテーションとして見ており（`ProxyRulesArchTest.java:113`、`:117`）、同じクラスの中からの呼び出しと private のメソッドを止める。
  付け忘れは method security では止まらない。リファレンスは「unannotated methods are not secured」と書き、URL の catch-all の規則を勧める（`method-security.adoc:305-306`）。こちらは `anyRequest().authenticated()`（`SecurityConfig.java:102-103`）が catch-all で、役割の付け忘れは下の認可の表のテストで止める。
  向こうの方針の関数 `authorize` の代わりに、自作の方針の関数を置かない。見える範囲は SQL、役割は method security というフレームワークと DB の仕組みで足りるためである。
- **HTTP の応答の分け方**：向こうの表（見えなければ 404、見えるが許されなければ 403、状態の不一致と楽観排他は 409、業務のルールは 422）は、こちらの規約とすでに同じである。
  `docs/web-api/status-codes.md:12` は参照権限のないリソースに 404 を返すとし、`:64-66` は参照権限のない GET は 404、参照できるが更新できなければ 403 とする。ADR-062 は 404、409、422 を例外の型に対応づける。
  規約は変えず、新しい ADR から参照する。
- **認可の表のテスト（組み合わせの網羅と BOLA を 1 つにする）**：向こうは、方針と遷移の単体テスト（ADR 0045 の「網羅のテスト」）と、spec から流す BOLA の結合テスト（`apps/api/src/bola.test.ts`）の 2 つを持つ。
  こちらでは見える範囲が SQL に、役割がアノテーションにあるので、単体テストでは確かめられない。Spring と DB を起動した MockMvc の結合テスト 1 つにまとめる。
  - 行は、commit した `openapi/openapi.yaml` の operation である。テストの側の enum の定数と spec の operation が一致することを、`ApiContractCoverageTest` と同じ形（swagger-core で spec を読む。`ApiContractCoverageTest.java:37`、`:95-109`）で確かめる。
  - 列は、操作する人の種類である。持ち主、同じテナントの別の member、同じテナントの admin（持ち主でない）、別のテナントの member の 4 つにする。admin の列の期待は #188 で決める。
  - 期待の表は、操作の enum の `switch` 式で書き、`default` を書かない。Java の `switch` 式は全ての定数を覆わないとコンパイルに失敗するので、操作を足すと期待の表の足し忘れが止まる（「確かめたこと」）。向こうの `satisfies Record<操作, …>` と同じ役割である。本番の方針からは作らない（向こうと同じく、自分と比べるだけになるため）。
  - 拒否を期待する組の判定は次のとおりにする。
    - パスに id を持つ操作と、名前が `id` か `Id` で終わる query の引数を持つ操作では、応答が「存在しない id」で叩いたときと同じ status と本文になること。id の選び方は Spectral の `public-id-uuid`（`.spectral.yaml:141-149`）と同じにする。向こうはパスの id だけを見るが、こちらの `GET /api/payments` は注文の id を query の `orderId` で受け取り（`openapi/openapi.yaml:306-320`）、存在しない注文に空の `items` を返す。パスだけを見るとこの操作が抜ける。
    - 書き込みの操作では、業務のスキーマの行が変わらないこと。向こうと同じく、行の数ではなく中身を比べる（`apps/api/test/bola.ts:178-196`）。
  - 期待と違ってよい組は、理由と Issue の番号付きの例外の一覧に書く。理由が空の例外と、spec にない操作の例外は失敗にする（向こうの `findBolaViolations`、`bola.ts:83-111`）。#188 と #363 が入るまでの組は、この一覧に載せて各 Issue で消す。
  - 判定の部分は、テストの側の fixture の Controller（持ち主で絞らない GET と、404 を返すが他人の行を書き換える PUT）でも動かし、違反を検出することを確かめる（向こうの `bola-fixtures.ts`）。
  - 利用者は `AppUsers.inTenant`（`AppUsers.java:53`）で作るが、今は利用者の ID が `USER_ID` の 1 つに固定（`:30`）なので、利用者の ID を引数で受け取れるようにする。
  OWASP API Security Top 10 2023 の API1 は、クライアントの入力でレコードに触る全ての関数で認可を確かめることと、認可の仕組みのテストを書き、失敗する変更を出さないことを勧める。Authorization Cheat Sheet は、認可の単体テストと結合テストを書くことを勧める。
- **認可のライブラリと方針エンジンを入れない**：Spring Security の method security だけを使い、`spring-security-acl`、Cedar、OPA、OpenFGA、SpiceDB、Cerbos は入れない。
  役割は `ROLE_ADMIN` と `ROLE_MEMBER` の 2 つ（`authentication-and-session.md:52`）、関係は注文の持ち主の 1 種類で、設定と運用の費用が見合わないためである。
  ACL は、リソースごとに許可の行を持つ仕組みで、リファレンスの「Domain Object Security (ACLs)」の章が扱うような、利用者ごとに共有先が変わる要件のためのものである。
  関係が増えたら（部署の階層、代理）見直す。

## 取り込まないもの

- **許可の証（`Permit`）を domain の遷移の引数に取る形**：向こうは、方針の関数の呼び忘れを型で止めるために、domain の遷移の関数が証を必須の引数に取る（ADR 0045 の「許可の証」）。こちらでは、次の理由で採らない。
  - Java の型だけでは、証を作る場所を方針に限れない。証の型を domain に置くと application から作れるようにコンストラクタを公開する必要があり、application に置くと domain が application に依存する（ADR-002 の向き）。作る場所を限るには ArchUnit の規則が要る。向こうも、作る場所を 1 か所に限る検査はまだない（ADR 0045 の Consequences、`docs/domain-model/authorization.md` の NOTE）。
  - 証が守るのは domain の遷移だけで、BOLA の主な形である読み取り（一覧と詳細）は守らない。こちらでは読み取りを SQL の条件と認可の表のテストで守り、書き込みも同じ読み取りの経路を通る（CommandHandler が Repository で集約を読んでから遷移を呼ぶ）。
  - Listener から呼ぶ遷移（決済の記録。`OrderConfirmedListener` が `ChargeOrderCommandHandler` を呼ぶ）には要求した人がいないので、人のいない証を作る抜け道が要る。
  - こちらの domain には、人に依る業務の規則がまだない。職務分掌のような規則が入ったら、利用者の ID を値で渡す形（「取り込むもの」の線引き）で足り、証は要らない。
  呼び忘れは、操作を足すと期待の表がコンパイルで止まり、その組の判定が拒否を確かめることで止める。
- **自作の方針の関数 `authorize(actor, 操作, 対象)`**：上の「役割による操作の許可」のとおり、見える範囲は SQL、役割は Spring Security の method security に任せる。自作の関数で同じ判断を持つと、2 か所に分かれる。
- **方針の単体テストと遷移の単体テストの 2 本立て**：判断が SQL とアノテーションにあるので、単体テストでは確かめられない。API の結合テスト 1 つにする。
- **向こうの操作する人の 7 種類**：上長と経理の役割を持つ向こうの業務に合わせた種類である。こちらは 4 種類にする。
- **拒否のログを別に持つこと**：向こうと同じく足さない。ログは利用者の内部 ID を `user.id` で持つ（`docs/observability/log-output-points.md:41`）。拒否の急増の検知は、ログの文脈を決める #321 と、可観測性の送り先に回す。
- **フロントエンドの認可の線引き**（向こうの `docs/web-frontend/authorization.md`、`nav-items.ts`、`_accounting.tsx`）：こちらの `docs/frontend/authorization-ui.md:10-18` が、同じ出典（Future の Web フロントエンド設計ガイドライン）から、SPA の制御は誤操作を防ぐためで保護はバックエンドが担う、画面の判定は共通の親 route の `beforeLoad` にまとめる、と書いている。取り込むものはない。

## ほかの Issue との関係

- **#188**（注文と決済の参照と更新を、注文の持ち主に限る）：#315 は #188 の前提を決める。置く層（Repository の検索条件で絞り、application が利用者の ID を渡す）、応答（見えなければ 404、決済記録は注文の見える範囲に従う）、強制の方法（認可の表のテスト）は、この調査で決めた。
  持ち主の表し方（作成した利用者の ID を注文に持つか）と、admin に全件を見せるかは #188 で決める。#188 で、認可の表のテストの例外の一覧から #188 の組を消す。
- **#363**（tenant_id と RLS）：別のテナントの列の期待は、RLS が入るまで例外の一覧に #363 で載せる。RLS のカタログのテストと、別のテナントの行を読めないテストは #363 にある。
- **#380**（招待）：最初の役割で許す操作である。招待、役割の変更、所属を外す API は、CommandHandler に `@PreAuthorize("hasRole('ADMIN')")` を付け、member の列で 403 を期待する。
- **#540**（Idempotency-Key を必須にする）：入った後は、認可の表のテストの書き込みの要求にキーを付ける。
- **#541、#564**（Problem Details の type）：権限不足の 403 は `about:blank` のまま（`status-codes.md:26`）で、変えない。
- **#316、ADR-101**：役割は所属の行に持ち、テナントを選んだときに `ROLE_ADMIN`、`ROLE_MEMBER` の権限にする。method security はこの権限を見る。
- **ADR-077**：契約の operation の一覧を spec から読み、テストの側の宣言と突き合わせる形を、認可の表のテストでも使う。ADR-077 は変えない。

## 確かめたこと

- Java 25（`javac 25.0.5`、こちらの `backend/build.gradle:34` の版）で、enum の 3 つの定数のうち 2 つだけを覆う `default` のない `switch` 式は、`the switch expression does not cover all possible input values` でコンパイルに失敗する。
- Spring Security の最新は 7.2.0（Spring Boot 4.2.0 が使う版）で、こちらは 7.1.2 である。7.1 と 7.2 の What's New に、method security とオブジェクトの単位の認可を変える項目はない（7.1 は MFA と `ConditionalAuthorizationManager`、7.2 は One-Time Token、Web、OAuth 2.0 の項目）。判断を変える機能はないので、上げることは要らない。
- 7.2.0 のソースで、`AuthorizationDeniedException` は `AccessDeniedException` を継承する（`core/.../AuthorizationDeniedException.java:30`）。

こちらのリポジトリ（main、commit `63e5c9f`）で、次を確かめた。

- `backend/src/main` に `@EnableMethodSecurity`、`@PreAuthorize`、`hasRole` はない。認可は `SecurityConfig` の `authorizeHttpRequests` の `anyRequest().authenticated()`（`SecurityConfig.java:84-104`）と、`TenantSelectionFilter` のテナントの選択だけである。
- 注文の CommandHandler は、注文を ID だけで読む（`ConfirmOrderCommandHandler.java:41-45`、`OrderRepository.findById`）。持ち主の条件はない。`docs/backend/architecture.md:238-241` がこの割り切りを書き、#188 で扱うとしている。
- `OrderQueries.findDetails(String)` は利用者を受け取らず、`ChargeOrderCommandHandler`（`:73`）が Listener の経路から呼ぶ。利用者を受け取る形に変えるときは、Listener の経路の呼び方も決める必要がある（#188）。
- 契約の id を持つ操作は、パスの `{orderId}` の 4 つ（`/api/orders/{orderId}`、`/cancel`、`/confirm`、`/lines`）と、query の `orderId` の `GET /api/payments` である（`openapi/openapi.yaml:156`、`:188`、`:227`、`:266`、`:306-320`）。
- `docs/frontend/authorization-ui.md:10-18` は、向こうの `docs/web-frontend/authorization.md` と同じ線引きを書いている。

向こうのリポジトリ（`origin/main`、commit `29ba897`）で、次を確かめた。

- `authorize` は申請の「下書きを作る」だけが実装済みで、拒否のない操作なので証を直接返す（`packages/claim/src/application/authorize.ts`）。証を作る場所を限る検査と網羅のテストはない（`docs/domain-model/authorization.md` の NOTE）。
- BOLA のテストは実装済みである。spec のパスに `{...}` を持つ操作を取り出し（`apps/api/test/bola.ts:38-47`）、持ち主でない社員の応答が 404 の Problem Details で、書き込みで全表の中身が変わらないことを見る（`:113-152`、`:178-196`）。
- 業務の route はまだ id を持たないので、`factories` と `exceptions` は空で、仕組みは fixture の `/api/notes/{id}` だけで動いている（`apps/api/src/bola.test.ts:22-25`、`apps/api/test/bola-fixtures.ts`）。
- メニューの出し分け（`apps/web/src/lib/nav-items.ts`）と経理の画面の入口（`apps/web/src/routes/_authenticated/_accounting.tsx`）は、画面の制御で本当の認可は API が行う、とコメントに書いている。

## 出典

- 向こうの ADR 0045（`docs/adr/0045-authorization-policy-in-application-with-permit-token.md`）、`docs/domain-model/authorization.md`、`docs/web-frontend/authorization.md`、`apps/api/test/bola.ts`、`apps/api/test/bola-fixtures.ts`、`apps/api/src/bola.test.ts`
- Spring Security 7.2.0 のリファレンス（[Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)、[Spring Data Integration](https://docs.spring.io/spring-security/reference/servlet/integrations/data.html)、[Domain Object Security (ACLs)](https://docs.spring.io/spring-security/reference/servlet/authorization/acls.html)、[What's New](https://docs.spring.io/spring-security/reference/whats-new.html)）。本文は tag `7.2.0` の `docs/modules/ROOT/pages/` の asciidoc で読んだ。
- Spring Security 7.1.0 の What's New（tag `7.1.0` の `docs/modules/ROOT/pages/whats-new.adoc`）
- Spring Security と Spring Boot のリリース（[spring-security の tag](https://github.com/spring-projects/spring-security/tags)、[Spring Boot v4.2.0](https://github.com/spring-projects/spring-boot/releases/tag/v4.2.0)）
- OWASP API Security Top 10 2023 [API1:2023 Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/) の「How To Prevent」
- OWASP [Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) の「Ensure Lookup IDs are Not Accessible Even When Guessed or Cannot Be Tampered With」と「Create Unit and Integration Test Cases for Authorization Logic」
- [JLS (Java SE 25) 15.28.1](https://docs.oracle.com/javase/specs/jls/se25/html/jls-15.html#jls-15.28.1)（switch 式の switch block は網羅でなければならない）

## 作業の Issue

- #568（認可の置き場所の ADR と docs、`@EnableMethodSecurity`、ArchUnit の規則、認可の表のテスト）
- #188 と #380 には、前提をコメントで足した。
