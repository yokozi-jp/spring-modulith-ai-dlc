# トレースの範囲と計装の入れ方（#320 の調査）

この文書は #320 の調査の記録で、merge しない。
結論は #320 のコメントと同じで、作業は #587 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `63e5c9f`）である。

## 結論

一部採用とする。
向こうの ADR 0048 のうち、次を取り込む。

- 計装が取る属性を発生源で絞る。
  こちらでは、外向きの HTTP の span の URL から query、fragment、userinfo を落とす。
- span に禁止値が出ないことを、本物の計装を通したテストで確かめる。
- 1 回の要求で出る span を見直し、数だけ増える span を切る。
  こちらでは、Spring Security の filter chain の observation を止める。
- `tenant.id` はトレースの属性に載せてよいが、メトリクスの tag には載せない。

送るシグナルをトレースだけに絞ること、計装のライブラリを一つずつ足す形、SDK を 1 つの package に閉じる形、送り先がないときに SDK を始めない形は取り込まない。
こちらは Spring Boot が Micrometer の Observation で計装を入れ、SDK の組み立ても Spring Boot が持つ。
この形は、向こうが一括パッケージを避けて得たかったこと（何が出るかを導入側が把握する）を、Spring Boot の公式の経路で満たしているためである。

外向きの HTTP の URL を Collector ではなく発生源で絞るため、ADR-015 の「禁止値が入る経路が見つかった場合は、その属性を Collector で消す」（ADR-015:82-84）を改める。
作業の Issue を 1 つ起こし（#587）、テナントの規則は #378 に足した。

## 取り込むもの

- **外向きの HTTP の span の URL を発生源で絞る**（ADR 0048:19、`policy.ts:20-32`、`url.ts:4-12`）。
  向こうは `fetch` の `url.full` と `url.query` を、`urlWithoutQuery()` でオリジンとパスだけにする。
  こちらの `RestClient` の span は、Spring Framework の `DefaultClientRequestObservationConvention` の `http.url` に、`URI.toASCIIString()` をそのまま入れる（v7.0.11 の `DefaultClientRequestObservationConvention.java:175-177`）。
  この値は userinfo、query、fragment を含む。
  使い捨てのテストで、`http://USERX:PASSX@…/v1/x?token=TOKENVALUE#frag2` への呼び出しの span が、`http.url` にこの文字列をそのまま持つことを確かめた（下の「確かめたこと」）。
  URI テンプレートの変数で値を渡しても、`http.url` は展開した値を持つ。
  ADR-015 は query string と URL fragment を「発生源で渡さない値」に挙げる（ADR-015:63）が、外部の API が query を求めるときに、それを守る仕組みがない。
  今の `PaymentGatewayClient` は `/v1/charges` に query を付けない（`PaymentGatewayClient.java:113-120`）ので、今は漏れていない。
  直し方は、Spring Framework が拡張点として用意する `ClientRequestObservationConvention` の Bean にする。
  `DefaultClientRequestObservationConvention` を継承し、`requestUri`（protected、`:175`）で scheme、host、port、path だけを残す。
  Spring Boot 4.1.2 の `RestClientObservationAutoConfiguration` は、この型の Bean があればそれを使う。
  低い濃度の `uri`（`:105-107`）は URI テンプレートなので値を持たないが、テンプレートを使わずに文字列で URL を渡すと値が入る（下の「確かめたこと」）。
  `uri` はメトリクスの tag にもなるので、同じ Bean で query と fragment を落とす。
  受信の span の `http.url` は `getRequestURI()` で、query を含まない（v7.0.11 の `DefaultServerRequestObservationConvention.java:179-184`）。
  OIDC の callback（`/login/oauth2/code/keycloak?code=…&state=…`）の span も、`http.url=/login/oauth2/code/keycloak` だけを持つことを確かめた。
  受信の側は変えない。
- **Collector ではなく発生源で絞り、ADR-015 を改める**。
  ADR-015 は、span の属性に allowlist をかけず、禁止値が入る経路が見つかったら Collector で消すとしている（ADR-015:82-84）。
  上の `http.url` は、その経路が見つかった例である。
  ADR-070 は、SQL の文について「発生源で入れない計装を選べる」として、Collector で消す案を退けた（ADR-070 の選択肢3）。
  OpenTelemetry の「Handling sensitive data」も、まず集めないことを先に挙げる。
  発生源で絞れば、Gradle のテストで確かめられ、メトリクスの `uri` の tag も同じ Bean で守れる。
  ADR-015 は Accepted なので、新しい ADR で一部を改め、ADR-015 に `Amended by` を書く（`docs/adr/conventions.md:59-63`）。
- **span に禁止値が出ないことを、本物の計装を通したテストで確かめる**（ADR 0048:20 の保証 (b)、`policy.test.ts:46-70`）。
  向こうは、ヘッダー、body、クエリ文字列、SQL の値を入れた呼び出しで、span の属性に値が出ないことを Vitest で確かめる。
  こちらは、SQL を `JooqObservationConfigTest` で確かめるだけで、HTTP の受信、外向きの HTTP、Redis の span を確かめるテストがない。
  ADR-015:83 は「Spring Boot が既定で付ける属性と tag に禁止値が入る経路は確認されておらず」と書くが、確かめるテストはない。
  `@SpringBootTest` に `SpanProcessor` の Bean を足せば、Spring Boot の OpenTelemetry の自動構成が終わった span を渡す（下の「確かめたこと」で使った形）。
  `CapturedLogRecords` が LogRecord を受ける形と同じである。
  テストは、query、fragment、userinfo を持つ受信と外向きの呼び出し、session を作る要求を流し、どの span の属性にも値が出ないことを確かめる。
- **1 回の要求で出る span を見直し、Spring Security の filter chain の observation を止める**（ADR 0048:15 の「SERVER の span が 1 本だけ」、`tracing.unit.test.ts:34-35`、`policy.ts:12-13` の `ignoreConnectSpans`）。
  向こうは、受信の span を 1 つの計装に絞り、時間を持たず数だけ増える `pg-pool.connect` の span を切った。
  こちらで匿名の `GET /api/products` を 1 回流すと、span は 4 本出た。
  受信の `http get`（SERVER）のほかの 3 本は Spring Security の span で、`security filterchain before` と `security filterchain after` は filter ごとの event を 15 から 17 個持つ。
  `authorize request` は、匿名の要求ごとに `AccessDeniedException` の stacktrace を event に持つ。
  Spring Security 7.1.2 は、`SecurityObservationSettings` の Bean がなければ、すべての observation を出す（`observability.adoc:282`）。
  Spring Boot 4.1 は、`management.observations.enable.<名前の接頭辞>=false` で observation を止められる（Spring Boot リファレンスの「Preventing Observations」）。
  `management.observations.enable.spring.security.filterchains=false` で filter chain の 2 本を止める。
  認証と認可の span は、403 を調べるときに要るので残す。
  止めると、保存先へ送る span が要求ごとに 2 本減り、残る span で要求の流れを読みやすくなる。
  テストは、1 回の要求で SERVER の span が 1 本で、filter chain の span がないことを確かめる。
- **`tenant.id` はトレースの属性に載せてよいが、メトリクスの tag には載せない**（ADR 0048:19）。
  #378 は、ログとトレースの属性にテナントの `public_id` を入れる（受け入れ条件の 1 つ目）。
  #321 は、その項目名を `tenant.id` にし、`user.id` と合わせてログの MDC に載せると決めた。
  Micrometer の Observation では、低い濃度の key value はメトリクスの tag と span の属性の両方になり、高い濃度の key value は span の属性だけになる（Micrometer Observation のリファレンス）。
  テナントや利用者を低い濃度の key value にすると、メトリクスの系列がテナントの数だけ増える。
  トレースに入れるときは、高い濃度の key value にだけ入れ、メトリクスの tag に出ないことをテストで確かめる。
  この規則は、トレースにテナントを入れる #378 に足した（新しい Issue には入れない）。

## 取り込まないもの

- **送るのはトレースだけにする**（ADR 0048:14）：こちらは、ログ、トレース、メトリクスを OTLP で Collector へ送り、ログの属性を Collector の allowlist で絞る（ADR-015、ADR-043）。
  向こうは Collector を置かず、ログは stdout の JSON Lines で保存先の機能でマスクする。
  こちらは本番の保存先を CloudWatch に決め、OTLP の経路を正本にしている（ADR-043）。
  向こうの理由（Collector を置く前提になる）は、Collector をすでに置くこちらには当たらない。
  ログの経路と文脈は #321 で扱う。
- **計装のライブラリを一つずつ明示して足し、一括パッケージを使わない**（ADR 0048:15）：こちらの計装は、Spring Boot が依存ごとに Micrometer の Observation を自動構成して入れる（Spring MVC、`RestClient`、Lettuce、Spring Security）。
  SQL だけは、Spring Boot に仕組みがないので jOOQ の `ExecuteListener` で作る（ADR-070）。
  向こうが避けた一括パッケージ（`auto-instrumentations-node`）に当たるのは、OpenTelemetry の Java agent である。
  こちらは Java agent も OpenTelemetry の Spring Boot starter（`io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`）も使わない。
  Spring Boot 4.1 のリファレンスは、Spring のチームが公式に支える OpenTelemetry の経路を、Micrometer と OTLP の exporter としている。
  どの observation が出るかは、上の「1 回の要求で出る span」のテストで確かめ、要らないものは `management.observations.enable.*` で止める。
  計装を一つずつ足す形より、フレームワークの仕組みに寄せる方針と合う。
- **Java agent の URL の sanitizer に寄せる**：Java agent 2.34.0 は、`url.full` と `url.query` の値のうち、決まった名前の query（`AWSAccessKeyId`、`Signature`、`sig`、`X-Goog-Signature` など）と userinfo だけを `REDACTED` にする（`HttpConstants.java:27-37`、`UrlSanitizer.java:19-33`）。
  こちらは Java agent を使わず、その機能は Micrometer の `http.url` に効かない。
  また、ADR-015 は query string の全体を発生源で渡さない値にしており、名前の一覧で消す形より広い。
  向こうと同じく query の全体を落とす。
- **SDK と exporter を import してよい場所を 1 つの package に閉じ、Lint で止める**（ADR 0048:16）：向こうは、入口ごとに SDK の初期化を書く重複を避けるため、`startTelemetry()` に集めた。
  こちらは Spring Boot の自動構成が SDK、exporter、propagator を組み立て、アプリのコードは SDK を組み立てない。
  `backend/src/main` で `io.opentelemetry` を import するのは、Logback の appender に `OpenTelemetry` を渡す `OpenTelemetryAppenderInitializer.java` だけである。
  重複する初期化がないので、閉じる先の package を作る理由がない。
  ArchUnit で `io.opentelemetry.sdk` の import を止めることも考えたが、Spring Boot が組み立てる以上、アプリのコードが SDK を使う動機がなく、止める誤りが起きていない。
- **送り先の環境変数がないとき、SDK を始めない**（ADR 0048:18、`sdk.ts:32-53`）：こちらは `OTEL_EXPORTER_OTLP_ENDPOINT` に既定値を置かず、未設定なら起動に失敗する（`application.yaml:315-339`）。
  設定し忘れを起動の失敗で知らせる形で、向こうの「始めなかったことを起動のログで知らせる」より強い。
- **ブラウザの OpenTelemetry を採らない**（ADR 0048:22）：こちらは Faro Web SDK でブラウザの例外、画面、Web Vitals、`/api/**` の trace を Collector へ送り、フロントエンド用の pipeline で許可した値だけを残す（ADR-068）。
  向こうより先に進んでおり、変える理由がない。
- **ローカルの `grafana/otel-lgtm`**（ADR 0048:21）：こちらも同じイメージを使う（`docs/observability/runbook-local-traces.md`）。
- **OpenTelemetry の semantic conventions の HTTP の名前に切り替える**：Spring Boot 4.2.0 は `management.observations.conventions=open-telemetry` で、受信の span と指標を `http.route`、`url.path` などの名前に切り替えられる。
  Spring Framework 7.0.11 と 7.1.0 には受信の OpenTelemetry の convention しかなく、`RestClient` は Spring Boot 4.2.0 でも `DefaultClientRequestObservationConvention` のままである（`RestClientObservationAutoConfiguration.java:37-41`）。
  そのため、切り替えても外向きの `http.url` は変わらず、上の Bean が要る。
  名前を揃えるかは、指標の名前を揃える #563 で決める。
  受信の OpenTelemetry の convention（`OpenTelemetryServerRequestObservationConvention`）も、`url.path` に `getRequestURI()` を入れ、`url.query` と `url.full` を持たない（v7.0.11 の `:195`）ので、切り替えても受信の側の結論は変わらない。

## コメントへの回答

`#320` のコメントは、向こうの `packages/telemetry/src/domain/url.ts` と `src/infrastructure/policy.ts` の、トレースに載せる URL の扱いを見るよう求めた。

- `url.ts:9-12` の `urlWithoutQuery()` は、`new URL()` で解析して `origin + pathname` だけを返す。
  query、fragment、userinfo を落とす。
  受信（`@hono/otel` の `getUrlFull`）と外向き（`fetch` の `requestHook`）の両方が通す（ADR 0048:19）。
  向こうの受信が通すのは、`@hono/otel` の既定の `url.full` が query を含み、OIDC の callback の `code` と `state` が載るためである。
- `policy.ts:27-32` は、`fetch` の span の開始後に `url.full` を上書きし、`url.query` を空にする。
  ヘッダーを属性にする `headersToSpanAttributes` を書かないことで、ヘッダーを取らない。
  `policy.ts:10-18` は、pg の値、接続の span、SQL のコメント、DB への `traceparent` を明示して止める。
- こちらの受信の span は、Spring Framework の既定で query を持たないので、向こうの受信の処理に当たるものは要らない。
  外向きの span は query、fragment、userinfo を持つので、`urlWithoutQuery()` と同じ形（scheme、host、port、path だけ）を `ClientRequestObservationConvention` の Bean で入れる。
  `url.query` を空にする処理は、Micrometer の既定の convention が `url.query` を出さないので要らない。
- こちらのヘッダーと body は、Spring の HTTP の observation の key value にない（v7.0.11 の両方の convention の key value の一覧）。
  SQL は ADR-070 で発生源で入れない。
  Redis は、Spring Boot 4.1.2 が `includeCommandArgsInSpanTags` を `false` にした Lettuce の `MicrometerTracing` を入れる（`LettuceObservationAutoConfiguration.java:32-35`、Lettuce 7.5.2 の `MicrometerTracing.java:50-66`）ので、key と引数が span に出ない。
  Spring Session の key は session ID を含むが、span に出ないことを確かめた。

## ほかの Issue との関係

- **#321（ログの文脈とマスク）**：ログの経路、ログの文脈、マスクは #321 に任せる。
  #321 は `tenant.id` と `user.id` をログの MDC に載せると決め、#378 で実装する。
  この調査は、同じ値を span とメトリクスにどう載せるかの規則だけを足す。
  ADR-015 の禁止の一覧（「利用者へ結び付く ID」）は #321 が改め、この調査は ADR-015 の「出口の絞り込み」（ADR-015:76-84）を改める。
  同じ ADR の別の節なので、作業の Issue の ADR は、#378 の ADR と別に `Amended by` を 1 行ずつ足す。
- **#563（指標の名前と属性）**：Spring Boot 4.2 の `management.observations.conventions` で受信の名前を切り替えるかは #563 で決める。
  外向きの convention の Bean は、#563 で名前を切り替えても要る。
  #563 で `RestClient` の convention を別の形に変えるときは、URL を絞る処理を引き継ぐ。
- **#574（IdP とのタイムアウト）**：IdP のトークンの取得と UserInfo を Spring Boot の `RestClient.Builder` で作るように変えると、その呼び出しにも外向きの span が付き、この Issue の convention が効く。
  Spring Security 7.1.2 の既定のトークンの Client は、`ObservationRegistry` を持たない `RestClient.builder()` で作る（`AbstractRestClientOAuth2AccessTokenResponseClient.java:65`）ので、今は span にならない（ソースから読んだ。動かしていない）。
- **#378（ログ、トレース、キャッシュのキーにテナントを入れる）**：トレースにテナントを入れるのは #378 で、上の「`tenant.id` はメトリクスの tag に載せない」規則を #378 にコメントで足した。

## 限界

- span の属性は、`@SpringBootTest` の MockMvc と、Boot の `RestClient.Builder` から WireMock への呼び出しで確かめた。
  本物の Tomcat、Collector、CloudWatch では確かめていない。
- Spring Modulith の observability（`spring-modulith-starter-insight`）の span と、イベントの Listener の中の span は流していない。
- 外向きの span の exception の event は、`HttpClientErrorException` のメッセージを持ち、Spring の既定のメッセージは応答の body を含む（下の「確かめたこと」）。
  ADR-015 は、例外のメッセージに個人データが混ざることを発生源で禁じず、保存先の保護で扱うと決めている（ADR-015:73-74）。
  ADR-043 は `aws/spans` のロググループにもデータ保護ポリシーを置く。
  そのため、この調査では event を変えず、作業の Issue で docs にこの経路を書くだけにする。
- Spring Security の `SecurityObservationSettings` の Bean と、`management.observations.enable.spring.security.filterchains=false` の効き方の違いは確かめていない。
  作業の Issue のテストで、filter chain の span が消え、認証と認可の span が残ることを確かめる。

## 確かめたこと

こちらの worktree（`origin/main`、commit `63e5c9f`）に使い捨てのテストを置き、`./gradlew test --tests …` で流した（commit していない）。
テストは `@SpringBootTest`、`@AutoConfigureMockMvc`、`SharedTestConfiguration` に、終わった span を集める `SpanProcessor` の Bean を足した。
次を確かめた。

- `GET /api/products?secretq=QUERYVALUE#frag`（匿名で 401）の span は 4 本で、受信の span は `http.url=/api/products`、`uri=UNKNOWN` を持ち、`QUERYVALUE` はどの属性にもない。
  ほかの 3 本は `authorize request`、`security filterchain before`、`security filterchain after` である。
  filter chain の 2 本は filter ごとの event を 17 個と 15 個持ち、`authorize request` は `AccessDeniedException` の stacktrace の event を持つ。
- `GET /login/oauth2/code/keycloak?code=CODEVALUE&state=STATEVALUE`（302）の受信の span は `http.url=/login/oauth2/code/keycloak` で、`CODEVALUE` と `STATEVALUE` はどの属性にもない。
  同じ trace の Redis の span（`hmset`、`pexpireat`、`exists`）は、`db.operation`、`db.system`、`net.sock.peer.addr`、`net.sock.peer.port`、`net.transport`、`peer.service` だけを持ち、key（session ID を含む）を持たない。
- Boot の `RestClient.Builder` から `http://USERX:PASSX@127.0.0.1:<port>/v1/x?token=TOKENVALUE#frag2` を呼ぶと、外向きの span は `http.url` と `uri` に、userinfo、query、fragment をそのまま持つ。
  `uri` に値が入るのは、URI テンプレートを使わず文字列で URL を渡したためである。
- `uri(base + "/v1/y?token={t}", "TOKENVALUE2")` の span は、`uri=/v1/y?token={t}` で、`http.url` は `TOKENVALUE2` を含む。
- 404 の外向きの span は exception の event を 1 つ持ち、`exception.stacktrace` は `HttpClientErrorException$NotFound: 404 Not Found: "…"` で、WireMock の応答の body を含む。

primary source で次を確かめた（版は 2026-11-19 の時点）。

- 最新の GA：Spring Boot 4.2.0（4.1 系は 4.1.3）、Spring Framework 7.1.0（7.0 系は 7.0.11）、Micrometer 1.18.0、Micrometer Tracing 1.8.0、OpenTelemetry Java 1.68.0、OpenTelemetry Java instrumentation 2.34.0、Spring Modulith 2.2.0、Lettuce 7.9.0（各 artifact の Maven Central の `maven-metadata.xml`）。
  Spring Boot 4.1.2 は Spring Framework 7.0.10、Micrometer 1.17.2、Micrometer Tracing 1.7.2、OpenTelemetry 1.62.0、Lettuce 7.5.2 を管理する（`spring-boot-dependencies-4.1.2.pom`）。
  こちらは `opentelemetry-bom` 1.68.0 で上書きしている（`backend/build.gradle:182`）。
- 判断を変える新しい版の機能は、Spring Boot 4.2.0 の `management.observations.conventions=open-telemetry` だけで、受信の名前だけを変え、外向きの `http.url` は変えない（上の「取り込まないもの」）。
  Spring Framework 7.1.0 の `DefaultClientRequestObservationConvention` は 7.0.11 と同じである。
- `DefaultClientRequestObservationConvention`（v7.0.11）：`requestUri` は protected で、`context.getCarrier().getURI().toASCIIString()` を `http.url` にする（`:175-177`）。`uri` も protected で、URI テンプレートの path を返す（`:105-107`）。
- `DefaultServerRequestObservationConvention`（v7.0.11）：`http.url` は `getRequestURI()` である（`:179-184`）。
- Lettuce 7.5.2 の `MicrometerTracing` の 2 引数のコンストラクタは `includeCommandArgsInSpanTags` を `false` にし、Spring Boot 4.1.2 はそれを使う。
- Spring Security 7.1.2：`SecurityObservationSettings` がなければすべての observation を出す。observation の key value は class の名前、filter の位置、権限の一覧で、URL、query、principal の名前を持たない。
- Spring Boot 4.1：`management.observations.enable.<接頭辞>=false` で observation を止め、`ObservationPredicate`、`GlobalObservationConvention`、`ObservationFilter`、`ObservationHandler` の Bean を自動で登録する。
- OpenTelemetry Java instrumentation 2.34.0：Spring Boot 4 に対応する（CHANGELOG 2.23.0 の「Spring starter: support Spring Boot 4」）。URL の sanitizer は、決まった名前の query の値と userinfo だけを消す。
- OpenTelemetry semantic conventions v1.45.0：`url.full` は userinfo を含んではならず、決まった名前の query の値を `REDACTED` にすべきとする。

向こうのリポジトリ（`origin/main`、commit `29ba897`）で、次を確かめた。

- `packages/telemetry/src` は、`domain/url.ts`（12 行）、`infrastructure/policy.ts`（38 行）、`infrastructure/sdk.ts`（69 行）と、再 export だけの `index.ts` と `node.ts` である。
- `sdk.ts:59-60` は `metricReaders: []` と `logRecordProcessors: []` を渡し、トレースだけを送る。
- `policy.test.ts:46-70` は、`fetch` の span の属性に、ヘッダー、body、query の値が出ないことを確かめる。`:73-92` は、pg の span が `$1` の文だけを持ち、値を持たないことを確かめる。
- `apps/api/src/tracing.unit.test.ts:34-35` は、1 回の要求で SERVER の span が 1 本であることを確かめる。

## 作業の Issue

- #587

## 出典

- [Spring Framework v7.0.11 `DefaultClientRequestObservationConvention`](https://github.com/spring-projects/spring-framework/blob/v7.0.11/spring-web/src/main/java/org/springframework/http/client/observation/DefaultClientRequestObservationConvention.java)
- [Spring Framework v7.0.11 `DefaultServerRequestObservationConvention`](https://github.com/spring-projects/spring-framework/blob/v7.0.11/spring-web/src/main/java/org/springframework/http/server/observation/DefaultServerRequestObservationConvention.java)
- [Spring Framework v7.0.11 `OpenTelemetryServerRequestObservationConvention`](https://github.com/spring-projects/spring-framework/blob/v7.0.11/spring-web/src/main/java/org/springframework/http/server/observation/OpenTelemetryServerRequestObservationConvention.java)
- [Spring Framework リファレンス：Observability Support](https://docs.spring.io/spring-framework/reference/integration/observability.html)
- [Spring Boot リファレンス 4.1：Observability（Preventing Observations）](https://docs.spring.io/spring-boot/4.1/reference/actuator/observability.html#actuator.observability.preventing-observations)
- [Spring Boot リファレンス：Observability（4.2 の OpenTelemetry Semantic Conventions）](https://docs.spring.io/spring-boot/reference/actuator/observability.html)
- [Spring Boot v4.1.2 `RestClientObservationAutoConfiguration`](https://github.com/spring-projects/spring-boot/blob/v4.1.2/module/spring-boot-restclient/src/main/java/org/springframework/boot/restclient/autoconfigure/RestClientObservationAutoConfiguration.java)、[`LettuceObservationAutoConfiguration`](https://github.com/spring-projects/spring-boot/blob/v4.1.2/module/spring-boot-data-redis/src/main/java/org/springframework/boot/data/redis/autoconfigure/observation/LettuceObservationAutoConfiguration.java)
- [Spring Boot 4.1.2 の BOM](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.2/spring-boot-dependencies-4.1.2.pom)
- [Lettuce 7.5.2 `MicrometerTracing`](https://github.com/redis/lettuce/blob/7.5.2.RELEASE/src/main/java/io/lettuce/core/tracing/MicrometerTracing.java)
- [Spring Security 7.1.2 Observability](https://github.com/spring-projects/spring-security/blob/7.1.2/docs/modules/ROOT/pages/servlet/integrations/observability.adoc)
- [OpenTelemetry Java instrumentation v2.34.0 `HttpConstants`](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.34.0/instrumentation-api/src/main/java/io/opentelemetry/instrumentation/api/internal/HttpConstants.java)、[CHANGELOG](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.34.0/CHANGELOG.md)
- [OpenTelemetry Spring Boot starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/)
- [OpenTelemetry semantic conventions v1.45.0 の URL の属性](https://github.com/open-telemetry/semantic-conventions/blob/v1.45.0/docs/registry/attributes/url.md)
- [OpenTelemetry：Handling sensitive data](https://opentelemetry.io/docs/security/handling-sensitive-data/)
- [Micrometer Observation](https://docs.micrometer.io/micrometer/reference/observation.html)
- 向こう：`docs/adr/0048-opentelemetry-traces-only-with-explicit-instrumentation.md`、`packages/telemetry/src/domain/url.ts`、`packages/telemetry/src/infrastructure/policy.ts`、`packages/telemetry/src/infrastructure/policy.test.ts`、`packages/telemetry/src/infrastructure/sdk.ts`、`apps/api/src/tracing.unit.test.ts`
- こちら：ADR-015、ADR-043、ADR-068、ADR-070、`docs/adr/conventions.md`、`docs/observability/conventions.md`、`docs/integration/async-observability.md`、`backend/build.gradle`、`backend/src/main/resources/application.yaml`、`PaymentGatewayClient.java`、`JooqObservationConfig.java`、`OpenTelemetryAppenderInitializer.java`、`ObservabilityContractTest.java`、`CapturedLogRecords.java`、#321、#378、#563、#574
