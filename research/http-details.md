# HTTP の細部をそろえる（#334 の調査）

この文書は #334 の調査の記録で、merge しない。
結論は #334 のコメントと同じで、作業は #650 と #651 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `ee8bf60`）である。

## 結論

一部採用とする。

問いは 2 つあった。route にない method への 405 と `Allow`、応答の `X-Request-Id` である。

- **405 と `Allow`**：こちらはすでに同じ挙動である。Spring MVC が `Allow` 付きの 405 を `about:blank` の Problem Details で返す。向こうが `hono/method-not-allowed` で足した形（yokozi-jp/agent-software-factory#712）と同じで、コードを足さない。足すのは `Allow` を確かめるテストと docs の直しだけである（#651）。
- **`X-Request-Id`**：取り込まない。こちらは独自の request ID を作らず、trace ID を正本にする（ADR-015:49-50）。代わりに、ADR-013:53 が決めたまま実装がない Problem Details の `traceId` 拡張を付ける。向こうも全 Problem に `trace_id` を付けている（ADR 0035:22）。あわせて、実際の値と docs が合わない `instance` を外す（#650）。

## 比べたもの

### 向こう

- yokozi-jp/agent-software-factory#712（`hono/method-not-allowed`、merge commit `228f719`）と #720（`hono/request-id`、merge commit `0a40591`）、それぞれの Issue の #574 と #575
- `apps/api/src/app.ts`、`apps/api/src/http/middleware/logging.ts`
- `packages/shared-kernel/src/problem/problem.ts`、`apps/api/src/tracing.unit.test.ts`
- `docs/adr/0035-problem-details-for-api-errors.md`（以下 ADR 0035）、`docs/web-api/status-codes.md`、`docs/web-api/response-headers.md`、`docs/web-api/http-methods.md`、`docs/logging/web-api.md`

### こちら

- `docs/web-api/status-codes.md`、`headers.md`、`http-methods.md`、`edge-responsibilities.md`
- ADR-013、ADR-015、ADR-058、`docs/observability/conventions.md`、`log-messages.md`、`runbook-local-traces.md`
- `backend/src/main/java/com/example/demo/error/presentation/web/`（`ApiExceptionHandler`、`ApiProblemDetails`、`ApiErrorController`）、`OpenApiConfig.java`
- #309、#317、#321、#320、#310 の結論（重ならないように読んだ）

## 項目ごとの比較

| 向こうの規則                                                                           | 向こうの場所                                       | こちらの扱い                                                                                                                                                  | 判定                               |
| -------------------------------------------------------------------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| route にある path へ route にない method で来たら、`Allow` 付きの 405（`about:blank`） | `app.ts:75-76`、ADR 0035:34                        | Spring MVC が `Allow` 付きの 405 を返し、`ApiExceptionHandler` が `about:blank` にする。`ApiErrorContractTest.java:130-135` が status、type、title を確かめる | 同じ決定（フレームワークの仕組み） |
| `Allow` に `GET, HEAD` を入れる                                                        | #712 の Evidence                                   | 宣言した method だけ（`GET` だけの route は `Allow: GET`）。HEAD と OPTIONS は入らない                                                                        | 取り込まない                       |
| 405 を未認証でも返す（`csrfProtection` と `sessionGuard` の前に置く）                  | ADR 0035:36                                        | 認証の filter の後。未認証は 401、CSRF トークンのない更新系は 403 が先に返る                                                                                  | 取り込まない                       |
| route にない path は 404                                                               | ADR 0035:34、`status-codes.md:48`                  | method によらず 404（確かめた）                                                                                                                               | 同じ決定                           |
| 405 を `openapi.json` に書かない                                                       | ADR 0035:38                                        | `commonProblemResponses` の対象は 400、401、403、500。405 を書かない                                                                                          | 同じ決定                           |
| 全応答に `X-Request-Id` を付ける（`hono/request-id`）                                  | `app.ts:64`、ADR 0035:39、`response-headers.md:80` | 独自の request ID を作らない（ADR-015:49-50、`conventions.md:47`、`log-messages.md:61`）                                                                      | 取り込まない                       |
| リクエストの `X-Request-Id` を受け入れる（`[A-Za-z0-9_=-]`、255 文字まで）             | `docs/logging/web-api.md:66`                       | 受け入れる ID がない                                                                                                                                          | 当たらない                         |
| 全 Problem に `trace_id` を付ける                                                      | ADR 0035:22、`problem.ts:191-214`                  | ADR-013:53 と `log-messages.md:59` が `traceId` 拡張を返すと決めたが、実装がない                                                                              | 取り込む（#650）                   |
| `instance` を設定しない                                                                | `problem.ts` に `instance` を設定する箇所がない    | 要求のパスが入る。ADR-013:54 と `OpenApiConfig.java:194` の説明と合わない                                                                                     | 取り込む（#650）                   |
| 8000 オクテットは、実装が少なくとも受け付ける長さ                                      | `http-methods.md:65`                               | `http-methods.md:30` が「推奨上限」と書く。意味が逆である                                                                                                     | 直す（#651）                       |
| 414 は使わない                                                                         | `status-codes.md:40`                               | `status-codes.md:54` は URL の長さを 400 にまとめるとするが、実際は Tomcat が 400 の HTML を返す                                                              | 直す（#651）                       |

## 取り込むもの

### Problem Details に `traceId` を付け、`instance` を外す（#650）

こちらの docs は、利用者の問い合わせとログを結ぶ ID を Problem Details の `traceId` 拡張で返すと決めている。

- ADR-013:53：「リクエスト追跡が必要な場合は、不透明な `traceId` 拡張を返す」。
- `docs/observability/log-messages.md:59`：「利用者の問い合わせとログを結び付ける必要がある場合は、Problem Details の `traceId` 拡張で trace ID を返す」。
- `docs/database/postgresql-primary-keys.md:154` は、Spectral の `public-id-uuid` が `traceId` の property を対象にしないと書く。`traceId` が契約に載る前提になっている。

実装は返していない。`runbook-local-traces.md:22` は「Problem Details に `traceId` は含まれない」と書き、`OpenApiConfig.java:179-195` の `ProblemDetail` に `traceId` がない。
このため、利用者は失敗を問い合わせるとき、ログを探す手掛かりを伝えられない。

向こうは、全 Problem に `trace_id` を付ける（`problem.ts:191-214`）。応答の `trace_id` が受信の span の trace ID と同じことは `tracing.unit.test.ts:38-46` が確かめる。
向こうが `X-Request-Id` も付けるのは、成功した応答も含めて、利用者が問い合わせに伝える ID が要るからである。ただし ADR 0035:39 は、web が `X-Request-Id` を読んで画面に出すときに契約にすると書き、向こうの web は今読んでいない（`apps/web` に `X-Request-Id` と `trace_id` の参照がない）。

こちらで足す形は次のとおりである。

- **付ける場所**：`ApiProblemDetails.normalize`（`ApiProblemDetails.java:75`）の 1 か所である。変換の経路は、`ApiExceptionHandler.createResponseEntity`（`:182-193`）と `ApiErrorController`（`:42-43`）で、どちらも `normalize` を通る。Spring Security の 401 と 403 は `HandlerExceptionResolver` 経由で `ApiExceptionHandler` に届く（`SecurityConfig.java:77`、`:163`）。
- **使う仕組み**：Micrometer の `Tracer.currentSpan()` である。Spring Boot が `Tracer` を自動構成するので、自作の層は要らない。
- **付ける範囲**：span があるすべての Problem Details（4xx と 5xx）にする。向こうと同じで、条件を分けない方が、問い合わせのときに ID の有無で迷わない。
- **値の信頼**：クライアントが `traceparent` で trace ID を決められる（下の「確かめたこと」）。相関だけに使い、認可と冪等性の鍵にしない。向こうも、リクエストの ID はクライアントが決められる値なので、相関のためだけに使うと書く（`logging.ts:20-21`）。

`instance` は、Spring Framework 7.0.10 が要求のパスを入れる。`/error` へ転送された失敗では、元のパスではなく `/error` が入る。
ADR-013:54 は「特定の失敗を識別する URI を用意できる場合だけ」と定める。RFC 9457 3.1.5 の `instance` も、特定の失敗の発生を識別する URI である。
Spring Framework 7.1.0 は、これが RFC に合わないとして `instance` を入れなくなる（[spring-framework#35969](https://github.com/spring-projects/spring-framework/issues/35969)、commit `ac65288`）。
`frontend` は `instance` を読まない（`.instance` の参照がない）。`normalize` で `null` にすれば、今の版で 7.1 と同じ挙動になり、ADR-013 と合う。
`docs/web-api/versioning.md:30` は応答の項目の削除を後方互換を壊す変更とするので、`CONTRACT_VERSION` の上げ方は #650 で決める。

ADR-013 の `traceId` と `instance` の行を改めるので、新しい ADR を起こす（`docs/adr/conventions.md` の基準の「過去のアーキテクチャ方針を変更する」と「API 契約」に当たる）。

### 405 の `Allow` のテストと docs の直し（#651）

コードは足さない。次を直す。

- `ApiErrorContractTest` の 405 のケースに `Allow` の確認を足す。RFC 9110 15.5.6 は、405 に `Allow` を MUST とする。今のテストは status、type、title だけを確かめる。
- `status-codes.md:41` に、`Allow` の中身（宣言した method だけ）と、401 と CSRF の 403 が先に返ることを書く。
- `status-codes.md:54` の 414 と、`http-methods.md:30` の 8000 オクテットを、実際の挙動と RFC に合わせる（下の「確かめたこと」）。
- `QUERY` を `http-methods.md` に 1 段落で書く（下の「ほかに見つけたこと」）。

## 取り込まないもの

- **`X-Request-Id`**：次の 3 つの理由で取り込まない。
  - ADR-015:49-50 は trace ID を正本にし、独自の request ID を重ねないと決めた。#321 も `http.request.id` と `X-Request-Id` を取り込まないとした。受け取る ID と trace ID の 2 つがあると、問い合わせで伝えるのがどちらかを docs に書き分ける費用が増える。
  - 応答に付ける仕組みが Spring にない。Spring Boot 4.2.0 のリリースノートに、応答のヘッダーに ID を付ける機能はない。付けるなら、`OncePerRequestFilter` を自作する。
  - 失敗でない応答に ID を付ける必要を、こちらはまだ決めていない。失敗は `traceId` で足りる。
- **応答に trace context を返すヘッダー（`Server-Timing: trace`）**：W3C Trace Context Level 2（Candidate Recommendation）は、応答に trace context を返す形を `Server-Timing: trace;desc=00-<trace-id>-<span-id>-<flags>` とする（仕様のリポジトリの `spec/21-http_response_header_format.md`）。Spring Boot にも Micrometer Tracing にも、このヘッダーを付ける仕組みがなく、自作の filter が要る。`headers.md:47` は `Server-Timing` を全 API へ先回りして付けないとする。web がこの値を読む機能が決まってから改めて比べる。
- **405 を認証の前に返す位置**：向こうは `csrfProtection` と `sessionGuard` の前に置き、未認証でも 405 にする（ADR 0035:36）。こちらは Spring MVC の handler mapping が 405 を決め、これは Security の filter chain の後に来る。認証の前に返すには、Security の前で route を調べる自作が要る。未認証に route の有無を教える利点もなく、SPA は誤った method を送らない。
- **`Allow` に HEAD と OPTIONS を足す**：RFC 9110 10.2.1 は `Allow` を、対象のリソースが対応するとして示す method の一覧とし、15.5.6 は 405 に「現在対応している method の一覧」を MUST とする。Spring の 405 の `Allow` は、宣言した method だけで、GET の route でも HEAD を入れない。OPTIONS への応答は `GET,HEAD,POST,OPTIONS` を返す。7.1.0 のソースも同じである（`RequestMappingInfoHandlerMapping.java` の `getAllowedMethods`）。直すには `handleHttpRequestMethodNotSupported` を上書きして足すことになり、SPA は 405 の `Allow` を読まない。自作を足す費用に見合わない。
- **Tomcat が返す 400 の HTML と 505 を Problem Details にする**：Spring Boot 4.1.2 の設定にこれを変えるものはなく、Tomcat の `ErrorReportValve` を差し替える自作が要る。#651 の docs に、Problem Details にならない場合として書く。
- **Spring Framework 7.1 の `renderUnhandledExceptions`**（[spring-framework#37398](https://github.com/spring-projects/spring-framework/issues/37398)、[#37416](https://github.com/spring-projects/spring-framework/issues/37416)）：`ResponseEntityExceptionHandler` を継承した advice を置き換える機能で、7.1 へ上げるときに #309 の変換の箇所と一緒に検討する。この調査は 7.1 へ上げる理由を見つけなかった。

## ほかの Issue との関係

- **#309、#541**：`type` の付け方と変換の箇所は #309 の結論のまま変えない。#650 は `ApiProblemDetails.normalize` に `traceId` を足し、`instance` を外す。#541 も `ApiProblemDetails` を変えるので、どちらが先でもよいが、後の方が衝突を直す。
- **#317**：失敗を例外で返すか値で返すかは変えない。
- **#321、#320**：`http.request.id` と `X-Request-Id` を取り込まない結論は、#321 のまま変えない。#650 は、ログの `traceId` と応答の `traceId` が同じ値になるようにする。トレースの範囲と計装（#320）には触れない。
- **#310**：`Idempotency-Key` の鍵に、リクエストの ID も trace ID も使わない。#334 の追記と、#650 の docs の規則が同じ向きである。
- **#336**：応答の `traceId` を画面に出すか、どう出すかはこの調査の対象外で、#336（認証まわりの画面とエラーの画面）で決める。#650 は API の契約だけを扱う。
- **ADR-013、ADR-015、ADR-058**：ADR-013 は #650 の新しい ADR で改める。ADR-015 は変えない（trace ID が正本のまま）。ADR-058（type の URI）には触れない。

## 限界

- ALB と CloudFront が、リクエスト行とヘッダーの上限を超える要求や `QUERY` に何を返すかは確かめなかった。向こうの #720 は「CloudFront は `X-Amz-Cf-Id`、ALB は `X-Amzn-Trace-Id` を付け、`X-Request-Id` は付けない」と書くが、AWS の文書は読まなかった。
- Tomcat の最新（11.0.29）で、長すぎるリクエスト行への応答が変わるかは確かめなかった。確かめたのは、こちらが使う 11.0.27 である。
- アプリが返す 413 は確かめなかった。`backend/src/main` に 413 を返す箇所はなく（`status = 413` の参照は `ApiContractTest.java:375-390` の `/error` の title の確認だけ）、向こうも `bodyLimit` を持たない。`edge-responsibilities.md` の「アプリケーションへ届いた超過は 413 を返す」は、今のコードでは返さない。JSON の本文の大きさに、Tomcat とアプリが上限を持つかは確かめていない。
- `traceId` の試作は、使い捨ての `@SpringBootTest` に置いた。`ApiProblemDetails` を一時的に書き換えて確かめ、元に戻した（commit していない）。
- 画面に ID を出す形（#336）、`QUERY` を使う前に確かめること（前段と生成物の対応）は確かめなかった。Spring Framework の `addErrorResponseInterceptors`（#37416 が触れる）を `traceId` に使えるかも確かめていない。

## 確かめたこと

### 405 と `Allow`

こちらの `origin/main` に、使い捨ての `@SpringBootTest`（ランダムポートの Tomcat と MockMvc）を置いて確かめた。Spring Boot 4.1.2、Spring Framework 7.0.10、Tomcat 11.0.27 である。

- `POST` と `GET` を宣言した route への `DELETE`（ログイン済み、CSRF あり）は 405 で、`Allow: POST, GET`、`Content-Type: application/problem+json`、本文は `type: about:blank` だった。`GET` だけの route への `POST` と `DELETE` は `Allow: GET`、`PATCH` は `Allow: GET, POST` だった。
- `OPTIONS` は 200 で `Allow: GET,HEAD,POST,OPTIONS` だった。405 の `Allow` に HEAD と OPTIONS はない。
- 未認証の `DELETE`（CSRF あり）は 401、CSRF なしは 403（`/problems/csrf-token-invalid`）だった。未認証の `OPTIONS` は 401 だった。
- route にない path への `PUT` と `POST`（ログイン済み）は 404 だった。
- 実サーバーへの `TRACE` は 405 で、`Allow: HEAD, DELETE, POST, GET, OPTIONS, PUT`（その path が対応しない method を含む。Tomcat が付ける）、本文の `instance` は `/error` だった。不正な method 名（`FOO`）は 400 の `application/problem+json` だった。

### 414 と URL の長さ

実サーバーの Tomcat に生の HTTP を送った。

- 7.9 KB のリクエスト行は通った（未認証の 401）。8.3 KB と 70 KB のリクエスト行は 400 の `text/html;charset=utf-8`（Tomcat のエラーページ）だった。414 ではない。
- 9000 オクテットのヘッダーも 400 の HTML、HTTP バージョンが `HTTP/9.9` の要求は 505 の HTML だった。どれも Problem Details ではない。
- `server.max-http-request-header-size` の既定は 8KB である（Spring Boot 4.1.2 の `spring-configuration-metadata.json`）。
- RFC 9112 3 は「解釈する気のない長さの request-target を受け取ったサーバーは、414 を返さなければならない（MUST）」とし、続けて「すべての送り手と受け手が、少なくとも 8000 オクテットのリクエスト行に対応することを RECOMMENDED とする」とする。8000 は下限であり、上限ではない。こちらの `http-methods.md:30` は「推奨上限」と書き、向こうの `http-methods.md:65` は読み方が正しい。

### 応答の `traceId`

`ApiProblemDetails.normalize` を一時的に書き換え、`Tracer.currentSpan().context().traceId()` を `traceId` として足して確かめた。

- MVC の例外（405、500、404）、Spring Security の 401 と CSRF の 403、`/error` への転送（不正な method 名の 400 など）のすべてで、現在の span があり、`traceId` が付いた。
- `management.tracing.sampling.probability` は 1.0（`.env.test`）。`traceparent` を付けた要求では、応答の `traceId` が `traceparent` の trace ID になり、sampled flag が `00` の要求でも同じだった。このときログ（`API client error`）の `traceId`（ECS JSON）も同じ値だった。sampled flag が `00` の trace は Tempo に残らない（ADR-068:301）が、ログの検索はできる。
- 不正な `traceparent`（`garbage`）は無視され、新しい trace ID が付いた。クライアントは有効な形の trace ID なら自由に決められる。
- Tomcat が解釈できない要求（上の 8 KB 超など）の HTML には付かない。
- **テストでは `traceparent` が読まれない**：`@SpringBootTest` の既定では、`traceparent` を付けても応答の `traceId` は無関係な新しい ID になった。`ContextPropagators` が `NoopTextMapPropagator` だったためである。Spring Boot は、テストで tracing export を既定で無効にし（`spring.test.tracing.export` の既定は `false`）、propagator の Bean は `@ConditionalOnEnabledTracingExport` が付く（`OpenTelemetryPropagationConfigurations.java`、spring-boot-micrometer-tracing-opentelemetry 4.1.2）。`spring.test.tracing.export=true` を付けると、`traceparent`、`tracestate`、`baggage`、`b3` を読んだ。本番の設定は変えていないので、本番は読む。`traceId` を確かめるテストにはこの設定を付ける。

### 版

- 使っている版：Spring Boot 4.1.2（`backend/build.gradle:16`）、Spring Framework 7.0.10、Micrometer Tracing 1.7.2、Tomcat 11.0.27（Gradle のキャッシュ）。
- 最新の版（Maven Central、2026-11-23 時点）：Spring Boot 4.2.0、Spring Framework 7.1.0、Micrometer Tracing 1.8.0、Tomcat 11.0.29。
- Spring Framework 7.1 の変更のうち、この問いに関わるのは次の 3 つである。
  - `instance` を入れなくなる（#35969）。
  - `QUERY`（RFC 10008）に対応する（PR #34993）。
  - `WebMvcConfigurer#configureProblemDetails` で、`ResponseEntityExceptionHandler` なしの Problem Details の変換ができる（#37398、#37416）。
- Spring Boot 4.2 のリリースノートの全文を読んだ。可観測性の変更は、OpenTelemetry の semantic conventions、OTLP の共通設定、MDC のキー名、baggage の tag で、405、Problem Details、応答に ID を付ける変更はなかった。上げなくても #650 と #651 を実装できる。

### 向こう

- #712（`hono/method-not-allowed`）は、`app.ts:75-76` の 1 行で 405 を足し、`Allow: GET, HEAD` を返す。route は最初の要求で読む。
- #720（`hono/request-id`）は、自作の `requestContext` を消し、応答に `X-Request-Id` を付ける。受け付ける形は `[A-Za-z0-9_=-]` で 255 文字まで。
- 向こうの web は `X-Request-Id` と `trace_id` を読まない（`apps/web` を `git grep` で調べた）。

## ほかに見つけたこと

- **`QUERY`（RFC 10008）**：本文を持つ安全で冪等な検索の method として、Standards Track の RFC になった。Spring Framework 7.1.0 が対応する。こちらの `http-methods.md` は、本文で検索するなら POST を使うと書く。今は POST で検索する Endpoint がないので、取り込むものはなく、`http-methods.md` に 1 段落で書く（#651）。向こうは `QUERY` に触れていない。
- **`status-codes.md:55` の 501**：RFC 9112 3 は、長すぎる method 名に 501 を SHOULD とする。実サーバーは不正な method 名に 400 の Problem Details を返した（上の「確かめたこと」）。501 を仮実装専用とする規約のまま変えない。

## 出典

- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html)（10.2.1 Allow、15.5.6 405）
- [RFC 9112: HTTP/1.1](https://www.rfc-editor.org/rfc/rfc9112.html)（3 Request Line）
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)（3.1.5 instance）
- [RFC 10008: The HTTP QUERY Method](https://www.rfc-editor.org/rfc/rfc10008.html)
- [W3C Trace Context Level 2](https://www.w3.org/TR/trace-context-2/)（Server Timing の trace metric、仕様のリポジトリの `spec/21-http_response_header_format.md`）
- Spring Framework：[v7.1.0 のリリースノート](https://github.com/spring-projects/spring-framework/releases/tag/v7.1.0)と 7.1.0-M1 から RC2、`RequestMappingInfoHandlerMapping.java`（v7.1.0）、#35969 と commit `ac65288`、#37398、#37416、PR #34993
- [Spring Boot 4.2 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)、spring-boot-micrometer-tracing-opentelemetry 4.1.2 のソース（`OpenTelemetryPropagationConfigurations.java`、`OpenTelemetryTracingAutoConfiguration.java`）
- 向こう：yokozi-jp/agent-software-factory#712、#720、#574、#575、`apps/api/src/app.ts`、`apps/api/src/http/middleware/logging.ts`、`packages/shared-kernel/src/problem/problem.ts`、`apps/api/src/tracing.unit.test.ts`、`docs/adr/0035-problem-details-for-api-errors.md`、`docs/web-api/status-codes.md`、`response-headers.md`、`http-methods.md`、`docs/logging/web-api.md`
- こちら：`docs/web-api/status-codes.md`、`http-methods.md`、`headers.md`、`edge-responsibilities.md`、`versioning.md`、ADR-013、ADR-015、`docs/observability/conventions.md`、`log-messages.md`、`runbook-local-traces.md`、`backend/src/main/java/com/example/demo/error/presentation/web/`、`OpenApiConfig.java`、`SecurityConfig.java`、`ApiErrorContractTest.java`

## 作業の Issue

- #650：Problem Details に `traceId` を付け、`instance` を外す
- #651：405 の `Allow`、414、URL の長さ、`QUERY` の記述を実際の挙動に合わせる
