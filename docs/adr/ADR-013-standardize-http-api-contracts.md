# ADR-013: HTTP API 契約を標準化する

## Status

Accepted

## Date

2026-09-14

## Context

バックエンドには Spring MVC、Bean Validation、Spring Security、springdoc-openapi が導入済みだが、業務 API はまだ存在しない。
この段階でエラー、一覧取得、互換性、再試行、OpenAPI の規約を定めれば、公開後の破壊的変更を避けられる。

`/error` は未認証アクセスを許可しているが、Spring Boot の既定エラー表現に依存している。
`spring.mvc.problemdetails.enabled` の既定値は `false` であり、Spring MVC の例外、Spring Security がフィルタ内で返す 401 と 403、サーブレットコンテナから `/error` へ転送される失敗を単一の契約として検証していない。

既存の Jackson テストは `Instant` を RFC 3339 の `Z` 付き文字列として固定しているが、実際の HTTP 応答は検証していない。
OpenAPI は実行時に生成できるものの、操作 ID、応答スキーマ、認証要件、共通エラーを CI で検査していない。

現在の API 利用者はバックエンドと同時配備する SPA だけである。
独立した外部クライアント、長期サポート対象の旧版、重複すると損失を生む更新操作はまだ存在しない。
そのため、バージョニングと冪等性ストアを先に実装すると、実在しない互換性要件のためのコードと運用が増える。

## Decision

JSON API は `/api` 配下へ置き、次の契約を適用する。

### エラー表現

- `spring.mvc.problemdetails.enabled` を有効にし、RFC 9457 の `ProblemDetail` を使う。
- API の 4xx と 5xx は `application/problem+json` で返す。
- Spring MVC の既知例外と業務例外は、`ResponseEntityExceptionHandler` を継承した単一の `@RestControllerAdvice` で変換する。
- Spring Security の 401 と 403 は MVC の例外ハンドラを通らないため、API パスに限定した `AuthenticationEntryPoint` と `AccessDeniedHandler` で同じ表現を返す。
- `/error` へ到達する経路も契約テストへ含め、設定だけで契約を満たさない場合に限って `ErrorController` を追加する。
- `type`、`title`、`status`、`detail`、`instance` は RFC 9457 の意味を変えない。
- クライアントは `type` を問題種別の識別子として使い、`detail` を分岐条件に使わない。
- 一般的な HTTP エラーには `about:blank` を使う。
- 業務固有の問題を初めて公開するときは、管理下にある安定した HTTPS URI を `type` に使い、その URI で意味、HTTP status、対処方法を文書化する。
- `status` は実際の HTTP status と必ず一致させる。
- `detail`、検証エラー、ログ相関情報にスタックトレース、SQL、秘密情報、存在確認に使える認可情報を含めない。
- リクエスト追跡が必要な場合は、不透明な `traceId` 拡張を返す。
  `instance` は特定の失敗を識別する URI を用意できる場合だけ設定する。
- 入力検証エラーは 400 とし、業務固有の `type` と `errors` 拡張を使う。
  各要素は `pointer` と `detail` を持ち、`pointer` は可能な限り JSON Pointer で入力位置を示す。

### 成功応答とページング

- 単一リソースの成功応答を共通 envelope で包まない。
- 件数が数百件を超える可能性がある一覧はページングを必須とする。
- 既定方式は不透明なカーソルとし、要求は `cursor` と `limit`、応答は `items` と省略可能な `nextCursor` を持つ。
- `limit` の既定値は 20、上限は 100 とする。
- 並び順には一意な tie-breaker を含め、カーソルは並び順と検索条件へ結び付ける。
- クライアントはカーソルの内容を生成または解釈しない。
- 総件数は既定で返さない。
- ページ番号への直接移動が確認された画面だけは、OpenAPI に理由と上限を記録して offset 方式を使える。

### API バージョニング

- 共同配備する SPA だけが利用する間は API バージョンを導入せず、パスを `/api/...` とする。
- 後方互換な追加は同じ契約へ加える。
- 独立配備されるクライアント、外部利用者、または旧契約の並行保守が必要になった時点で、major version をパスへ導入する。
- 導入時は Spring Framework 7 の `ApiVersionConfigurer` を使い、`/api/v1/...` のような major version だけを公開する。
- 廃止時は `Deprecation`、`Sunset`、`Link` 応答ヘッダと移行期限を OpenAPI に記録する。

### 冪等性

- `Idempotency-Key` を全 POST と PATCH へ一律に要求しない。
- タイムアウト後の再試行で、決済、外部送信、重複作成などの回復困難な副作用が重複する操作だけに要求する。
- 対象操作では、キーの形式、スコープ、保持期間、再応答する status、header、body を OpenAPI に定義する。
- サーバは認証主体、操作、キーの組を副作用の前に原子的に予約し、リクエスト fingerprint が一致する完了済み要求には以前の結果を返す。
- 同じキーを異なる要求へ再利用した場合と、同じキーの処理が進行中の場合は、別々の RFC 9457 problem type で拒否する。
- 冪等性キーは「exactly once」を保証しない。
  DB の一意制約、トランザクション、outbox など、対象の副作用に応じた重複防止を併用する。
- IETF の `Idempotency-Key` 文書は失効した Internet-Draft であるため、将来 RFC が発行された時点で構文と応答規約を再評価する。

### OpenAPI と契約検査

- 既存の springdoc-openapi を維持し、OpenAPI 3.1 の文書をコードから生成する。
  現時点では、ツール対応を優先して OpenAPI 3.2 へ上げない。
- すべての操作に一意で安定した `operationId`、認証要件、要求スキーマ、成功応答、想定するエラー応答を記述する。
- 共通の Problem Details、検証エラー、ページ応答、CSRF header、対象操作の `Idempotency-Key` は components から参照する。
- Spectral は既定の OpenAPI ruleset とリポジトリ固有 ruleset を使い、構文だけでなく前項の必須要素を CI で検査する。
- Spectral の CLI は実装時点の検証済みバージョンへ固定する。
- Spectral は破壊的変更を検出する道具ではない。
  独立クライアントが生じた時点で、main ブランチの OpenAPI との差分検査を CI に追加する。
- MockMvc 契約テストで status、`Content-Type`、Problem Details の必須フィールド、Security の 401 と 403、入力検証、未処理例外、`/error`、OpenAPI 文書を検証する。
- HTTP 応答に `Instant` が現れる契約では、`Z` 付き文字列との完全一致を検証する。

## Consequences

### Positive

- MVC、Security、`/error` の失敗をクライアントから同じ規則で扱える。
- エラーの機械判定を可変な文章から安定した `type` へ移せる。
- API が公開される前から OpenAPI の欠落を CI で検出できる。
- カーソルページングにより、更新中の大きな一覧で offset 由来の重複と欠落を減らせる。
- 不要な API 版と冪等性ストアを今は持たず、必要条件が発生した時点で導入できる。

### Negative

- Security filter と MVC で同じ Problem Details を生成するための小さな共通処理が必要になる。
- カーソルはページ番号への直接移動に向かず、調査時に内容を読み取りにくい。
- バージョンなし API を独立クライアントへ誤って公開すると、後から version path を追加する移行が必要になる。
- Code-first OpenAPI では、実装と注釈が同時に誤っている場合を Spectral だけでは検出できない。

### Neutral

- カスタム problem type の URI 基点は、公開 API の管理ドメインが決まるまで確定しない。
- API の互換性要件が変わった場合は、バージョニングと OpenAPI 差分検査の判断を ADR へ追加する。
- 冪等性の保持期間は副作用とクライアントの再試行時間に依存するため、対象操作ごとに決める。

## Alternatives Considered

### Alternative 1: 独自のエラー DTO を定義する

- Description：`code`、`message`、`timestamp` などを持つ独自形式を全 API で返す。
- Pros：フィールドを自由に設計できる。
- Cons：標準の `ProblemDetail` と content negotiation を再実装し、クライアントも独自仕様へ固定される。

### Alternative 2: 最初から `/api/v1` を付ける

- Description：最初の API から major version を URL に含める。
- Pros：外部公開後も旧版を並行提供しやすい。
- Cons：現在は共同配備する SPA しかなく、旧版の利用者と保守要件が存在しない。

### Alternative 3: すべての POST に `Idempotency-Key` を要求する

- Description：更新操作を一律に冪等性ストアへ記録する。
- Pros：クライアントは同じ再試行方式を使える。
- Cons：安全に再実行できる操作にも保存、fingerprint、競合、期限切れの実装と運用が増える。

### Alternative 4: OpenAPI を手書きの design-first へ移す

- Description：YAML を正本とし、サーバコードを生成または手動追従させる。
- Pros：実装前レビューと複数言語の生成に向く。
- Cons：既存の springdoc を捨て、まだ存在しない業務 API のために生成工程と同期規約を増やす。

## References

- [RFC 9457: Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc9457)
- [Spring Framework 7.0.9: Error Responses](https://docs.spring.io/spring-framework/reference/7.0.9/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring Framework 7.0.9: API Versioning](https://docs.spring.io/spring-framework/reference/7.0.9/web/webmvc-versioning.html)
- [OpenAPI Specification](https://spec.openapis.org/oas/latest)
- [Spectral CLI](https://github.com/stoplightio/spectral/blob/develop/docs/guides/2-cli.md)
- [Zalando RESTful API Guidelines: Pagination](https://github.com/zalando/restful-api-guidelines/blob/main/chapters/pagination.adoc)
- [Expired Internet-Draft: The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [`ADR-006`](./ADR-006-utc-instant-absolute-time-policy.md)
- `backend/build.gradle`
- `backend/src/main/resources/application.yaml`
