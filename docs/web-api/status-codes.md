---
type: Convention
title: HTTPステータスコードの選択
description: APIが返すHTTPステータスコードの使い分けと、判断に迷いやすいユースケースごとの選択を定める規約。APIの成功応答とエラー応答のステータスコードを決めるとき、OpenAPIに想定するエラー応答を書くとき、Problem Detailsのtypeを決めるときに読む。
tags: [convention, web-api, http, future-arch-guidelines]
---

# HTTPステータスコードの選択

ステータスコードはRFC 9110の意味に従って使い分け、判断に迷ったら200、400、500のいずれかを使う。
形式とスキーマの違反は400、業務条件を満たさない入力は422、更新の競合は409にする。
参照権限のないリソースには、存在を隠すため404を返す。

## 前提

エラー応答の本文はRFC 9457のProblem Detailsとし、`status`を実際のステータスコードと一致させる。
この契約は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)が定める。
例外は、Tomcatがアプリへ渡す前に拒否する要求で、Problem Detailsにならない（[リクエストサイズの上限](edge-responsibilities.md#アプリより前に拒否される要求)）。
要求にspanがあるときは、すべてのProblem Detailsに`traceId`拡張でtrace ID（32桁の小文字の16進数）を付け、`instance`は設定しない（[ADR-117](../adr/ADR-117-add-trace-id-and-omit-instance-in-problem-details.md)）。
`/error`は、`HttpStatus`に定義のない4xxと5xx（499など）も転送元のステータスコードのまま返し、それ以外は500にする。
このとき`title`は、4xxなら400の文言、5xxなら500の文言にする。
このため、ログやダッシュボードには下の一覧にないステータスコードが現れることがある。
ステータス以上の意味がないproblemの`type`は`about:blank`にする。
業務固有のproblem typeは、パスを全部書いた相対URIにし、`title`をMessageSourceで日本語と英語に解決する（[ADR-058](../adr/ADR-058-use-path-absolute-relative-uri-for-problem-types.md)）。
422と409は`about:blank`にせず、失敗ごとのtypeを付け、404は`about:blank`のままにする。
422と409のtypeと`title`のキーの形と値は、[業務上の失敗の例外](../backend/class-roles/business-exception.md#例外httpログlistener-の対応)に従う（[ADR-127](../adr/ADR-127-derive-problem-types-from-failure-reasons.md)）。
OpenAPIの`ProblemDetail.type`の`x-extensible-enum`に、今あるtypeをすべて載せる。
CSRFトークンの検証に失敗した403は`/problems/csrf-token-invalid`にする。
対象は`/api/**`の更新系と`/logout`で、理由は[CSRFのCookie](authentication-and-session.md#csrfのcookie)にある。
テナントを選ぶ前に業務のAPIを呼んだ403は`/problems/tenant-not-selected`にする（[テナントを選ぶ](authentication-and-session.md#テナントを選ぶ)）。
権限不足の403は`about:blank`のままにする。
入力検証エラーは`/problems/validation-error`の400で、`errors`拡張に誤りのある入力のJSON Pointerと説明を入れる。
OpenAPIへのエラー応答の付け方は[OpenAPIのアノテーションとJavadoc](openapi-annotations.md#エラー応答)に従う。

## 使うステータスコード

- **200 OK**：参照の成功、本文を返す更新の成功、POSTによる検索の成功。
- **201 Created**：POSTによる同期的な作成の成功。
- **202 Accepted**：非同期処理の受付の成功。
- **204 No Content**：本文を返さない更新と削除の成功。
- **400 Bad Request**：リクエストの形式やOpenAPIスキーマで検証できる入力の誤り。
- **401 Unauthorized**：認証されていない。
- **403 Forbidden**：認証済みだが、操作またはエンドポイントを呼び出す権限がない。
- **404 Not Found**：パスで指定したリソースが存在しない、または参照する権限がない。
  `shared.failure`の`NotFoundException`は、`ApiExceptionHandler`が404にする（[業務上の失敗の例外](../backend/class-roles/business-exception.md)）。
- **405 Method Not Allowed**：エンドポイントが対応しないHTTPメソッドを指定された。
  `Allow`には、Controllerが宣言したメソッドだけが入る（RFC 9110 15.5.6）。
  たとえば`GET`だけのエンドポイントへの`POST`は`Allow: GET`になり、HEADとOPTIONSは入らない。
  405は、CSRFトークンの検査と認証を通った後に返る。
  CSRFトークンのない更新系の要求は、認証前でも403になり、認証されていない要求は401になる。
  認証後にエンドポイントのないパスを指定すると、メソッドによらず404になる。
  Spring Securityが受け付ける7つのメソッド（DELETE、GET、HEAD、OPTIONS、PATCH、POST、PUT）以外は、405にも404にもならず400になる。
  OpenAPIには、405を今は書いていない。
  `commonProblemResponses`が付けるのは、401、403、500と、入力を持つ操作の400である。
- **409 Conflict**：楽観ロックの競合、悲観ロックの取得失敗、一意制約違反。
  `shared.concurrency`の`ConflictException`は、`ApiExceptionHandler`が競合の種類ごとのtypeの409にする。
- **413 Content Too Large**：リクエストの本文が上限を超えた。ヘッダーの超過は、Tomcatが400を返す。
- **422 Unprocessable Content**：入力が業務処理の条件を満たさない（在庫不足で出荷指示できない場合など）。
  `shared.failure`の`BusinessRuleViolationException`は、`ApiExceptionHandler`が理由ごとのtypeの422にする。
  理由の定義は[業務上の失敗の例外](../backend/class-roles/business-exception.md#422-の理由の-enum)に従う。
- **429 Too Many Requests**：レート制限を超えた。
- **500 Internal Server Error**：DB接続エラー、SQLの実行エラー、外部APIの実行時エラーなどのシステムエラー。
- **503 Service Unavailable**：メンテナンス中、または一時的な過負荷。前段のリバースプロキシが返す場合もある。

次のステータスコードは使わない。

- **412 Precondition Failed**：`If-Match`を使わないため、楽観ロックの競合も409にする。
- **414 URI Too Long**：Tomcatは414を返さず、長すぎるリクエストを400にする。URLの長さはアプリに届かないので、アプリも414を返さない。
- **501 Not Implemented**：開発中の仮実装に限って使い、このコードを返すエンドポイントを本番へリリースしない。

## 判断に迷うユースケース

- **一覧検索の結果が0件**：200を返す。0件は正常な結果であり、一覧は件数やページング情報を返すため204にできない。
- **POSTで親リソースがない**：`POST /articles/1/comments`で記事1がなければ404を返す。
- **未対応のメソッド**：GETやPOSTへフォールバックせず405を返す。Spring Securityが受け付けないメソッドは400になる。
- **DELETEの2回目**：初回は204、2回目は404を返す。冪等性はリソースの状態についての性質であり、ステータスコードは変わってよい。
- **DELETE対象が既に削除済み**：他の利用者が先に削除した場合も、最初から存在しない場合と区別できないため404を返す。
- **参照権限のないリソースのGET**：403ではなく404を返す。403はリソースが存在するという手掛かりを与えるためである。社内向けのAPIでも同じにする。
- **参照権限はあるが更新権限がない**：PUT、PATCH、DELETEに403を返す。
- **エンドポイントを呼び出す権限がない**：403を返す。エンドポイントの存在はAPI文書で公開しているためである。
- **意味の近いコードで迷う**：200、400、500を使う。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
