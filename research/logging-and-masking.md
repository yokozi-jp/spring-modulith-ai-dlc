# ロガーの文脈とマスクの方式（#321 の調査）

この文書は #321 の調査の記録で、merge しない。
結論は #321 のコメントと同じで、作業は #378 に足した。
向こうは `yokozi-jp/agent-software-factory`（main、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `63e5c9f`）である。

## 結論

一部採用とする。
向こうの ADR 0049 の「文脈は毎行ロガーが付け、呼び出し側は渡さない」を取り込む。

- テナントの `public_id` と利用者の ID を、SLF4J の MDC に `tenant.id` と `user.id` で入れる。
  コンソールの ECS JSON は MDC を毎行に出し、OpenTelemetry の Logback appender は `mdcAttributesIncluded` で選んだ MDC を LogRecord の属性にする。
  呼び出し側の `addKeyValue` は要らない。
- MDC に入れる箇所は、テナントを束縛する 2 か所だけにする。
  リクエストの入口（`TenantSelectionFilter`）と、#376 の `@ApplicationModuleListener` の入口の Aspect である。
  Listener では `user.id` を入れない（ADR-051 は Listener の書き込みを利用者に結び付けない）。
- `user.id` をログの許可値にし、ADR-015 の「利用者へ結び付く ID」を改める。
  今の ADR-015:61 と `conventions.md:54` は `user.id` を禁じるように読め、`log-output-points.md:41` はアクセスログに `user.id` を求めていて、食い違っている。

マスクの三層のうち、1 層目の型と 2 層目の pino の `redact` は取り込まない。
こちらは Collector の allowlist（`keep_keys`）で出口を絞っており、denylist の `redact` より強い。
3 層目（CloudWatch Logs のデータ保護）は、こちらも ADR-015 で同じことを決めている。
例外の `detail` を落とす形、`http.request.id`、`trace_flags` と `level` の併記、メッセージコード、`client.address`、自前の `Logger` も取り込まない。
新しい作業の Issue は起こさず、#378（ログ、トレース、キャッシュのキーにテナントを入れる）に足した。
Issue #378 は「項目名は #321 の結果に合わせる」として #321 を待っている。

## 取り込むもの

- **文脈は毎行に付く仕組みで付け、呼び出し側は渡さない**（ADR 0049:15、`node-logger.ts:64-102` の `mixin`）。
  向こうは pino の `mixin` が、書くたびに `AsyncLocalStorage` の文脈と OpenTelemetry の active span を読む。
  Java では SLF4J の MDC がこの仕組みに当たり、自作の層は要らない。
  - Spring Boot の ECS 形式は、MDC のすべての値と SLF4J の key-value を JSON に出す（Spring Boot リファレンスの Structured Logging。4.1.2 の `ElasticCommonSchemaStructuredLogFormatter` は `getMDCPropertyMap` と `getKeyValuePairs` を `nested` で書くので、`tenant.id` は `{"tenant":{"id":…}}` になる）。
  - OpenTelemetry の Logback appender は、MDC を既定では取らない。2.33.0-alpha の `setMdcAttributesIncluded` で、取る MDC のキーを glob で選ぶ。`captureMdcAttributes` は非推奨である。
  - Collector の `keep_keys`（`docker/otel-collector/config.yaml:37`）に `tenant.id` と `user.id` を足す。足さなければ OTLP の出口で消える。
  - 入れる箇所はテナントを束縛する箇所と同じにする。HTTP は `TenantSelectionFilter.doFilterInternal`（`TenantSelectionFilter.java:71-87`）で、認証とテナントの両方をすでに読んでいる。`MDC.putCloseable` を try-with-resources で使い、`filterChain.doFilter` の後に必ず外す。
  - `@Async` の Listener へ、要求のスレッドの MDC は伝わらない。`spring.task.execution.propagate-context: true`（`application.yaml:52`）が伝えるのは、ContextRegistry に登録した `ThreadLocalAccessor` だけで、Micrometer 1.17.2 が `ServiceLoader` で登録するのは `ObservationThreadLocalAccessor` だけである（`micrometer-observation-1.17.2.jar` の `META-INF/services`）。trace と span はこれで伝わる。テナントは #376 の Aspect がイベントから取り出して束縛するので、そこで MDC にも入れる。要求の利用者が Listener の行に付かないのは、ADR-051 と合う。
- **`user.id` を毎行に付ける**（ADR 0049:15、`keys.md` の共通スキーマ）。
  OWASP Logging Cheat Sheet は、記録する属性の「Who」に、認証済みの利用者の識別子（例：利用者の表の主キー）を挙げる。
  こちらの利用者の ID は `tenancy.m_user.public_id` の UUID（`AppUserPrincipal.java` の `userId()`）で、氏名とメールアドレスを含まない。
  OpenTelemetry の semantic conventions の `user.id`（Development）と、ECS の `user.id` に名前が合う。
  ADR-015 の禁止の一覧は「利用者へ結び付く ID」と書くだけで、何を指すかが読めない。
  ADR-015 を改め、許すもの（`user.id` と `tenant.id`。どちらもアプリが採番した UUID）と、禁じたまま残すもの（IdP の `sub`、login ID、メールアドレス、認証 session ID）を分けて書く。
- **項目名は `tenant.id` にする**。
  OpenTelemetry の semantic conventions（commit `f5b61ab` の `model/`）にテナントの属性はない。
  ECS には `organization.id` があるが、こちらの規約（`log-messages.md` の「属性名」）は、semantic conventions にない属性を業務の対象の名前空間を付けた dot.case にするとしており、GLOSSARY の用語は「テナント」である。
  向こうの `tenant_id` は dot.case でないので使わない。
  値はテナントの `public_id` で、DB の `tenant_public_id` の列で引ける（#363 の Q24）。`m_tenant` の主キー `tenant_id`（bigint）とは別の値なので、docs にそのことを書く。

## 取り込まないもの

- **自前の `Logger` と、渡せるキーを型で絞る `LogFields`**（ADR 0049:14、:20 の 1 層目）：SLF4J の key-value は任意の文字列を受けるので、Java で同じことをするには SLF4J を包む自作の層が要る。こちらは出口の Collector の allowlist で、許したキーだけを残す。自作の層を保守する費用に見合わない。
- **pino の `redact` に当たる denylist**（ADR 0049:20 の 2 層目）：Spring Boot には `logging.structured.json.exclude` があり、コンソールの JSON から決めたパスを外せる。しかし denylist は綴りの違いと深い入れ子を守れないと、向こう自身が `node-logger.test.ts:222-235` に書いている。OTLP の経路はすでに Collector の allowlist で絞っている。コンソールの WARN 以上を送るロググループも、データ保護ポリシーで表示時にマスクする（`conventions.md` の「本番の保存先」）。
- **コンソールにも allowlist を掛ける**（`logging.structured.json.include`）：Collector の `keep_keys` と同じ一覧を `application.yaml` にも持つことになり、2 か所が食い違う。ECS の基本の項目（`@timestamp`、`log.level` など）もすべて書く必要がある。ADR-015 の Alternative 4 の「ログ、トレース、標準出力で別々の仕組みが要る」が今も当たる。
- **例外の `detail`、`where`、`parameters` を読まない形**（ADR 0049:20、`logger.ts` の `exceptionFields`）：こちらは ADR-015 で、例外を logger に渡して標準どおりに記録し、混ざった個人データは保存先の閲覧の制限と表示時のマスクで扱うと決めている。例外の詳細を消した最初の実装で調査できない場面が出た経緯が ADR-015 の Context にある。
- **`http.request.id` と `X-Request-Id`**（ADR 0049:16）：こちらは独自の request ID を作らず、trace ID を正本にし、問い合わせには Problem Details の `traceId` 拡張で返す（ADR-015、`conventions.md:46`、`log-messages.md:59-61`）。
- **`trace_flags` と `level` の併記**（ADR 0049:15）：OTLP の LogRecord は TraceFlags を型として持つ。`level` の併記は Lambda の絞り込みのためで、こちらは Lambda に置かない。
- **メッセージコード `event.code`**（ADR 0049:19）：こちらは WARN 以上の event 名をリポジトリ内で一意にし、識別子の属性を別に足さないと決めている（`log-operational-events.md` の「event 名による識別」）。#321 の問い（文脈とマスク）の外でもある。
- **`client.address`**（向こうの yokozi-jp/agent-software-factory#321、`web-api.md:58`）：向こうもまだ出しておらず、決める Issue が開いている。こちらは送信元 IP をアプリのログに出さない（`log-output-points.md:50`）。ALB のアクセスログは `client:port` を記録し（ALB の Access log entries）、前段の記録で足りる。アプリで取るなら、`X-Forwarded-For` を自分で読まず、Spring Boot の forwarded header の対応と Tomcat の `RemoteIpValve`（`server.tomcat.remoteip.internal-proxies` で信頼する proxy を絞る）に任せる。取ると決めるときは allowlist の変更として扱う。
- **Micrometer Tracing の baggage で文脈を運ぶ**：`management.tracing.baggage.correlation.fields` で baggage を MDC に写せるが、W3C の伝播では baggage が HTTP で外へ送られる（Spring Boot リファレンスの Baggage）。決済代行の呼び出しにテナントと利用者の ID が載るので使わない。
- **`logback-scoped-mdc`**：ScopedValue で MDC を持つ Logback の module で、1.0.0-rc0（2026-05-19）しか出ていない。Micrometer Tracing は trace ID を今の MDC に書くので、2 種類の MDC が混ざる。
- **テスト用のメモリ上の出力先**（`memory-destination.ts`）：こちらは Spring Boot の `OutputCaptureExtension` でコンソールの JSON を、`CapturedLogRecords` で OTLP の LogRecord を読む（`ObservabilityContractTest.java`）。Logback の `ListAppender` も使っている（`ApiExceptionHandlerTest.java:152`）。#378 のテストもこの形で書ける。
- **失敗のログにメッセージの id とエラーの文だけを書く**（`apps/worker/src/log.ts`）：こちらはすでに同じ規則を持つ。イベントの本文（`serialized_event`）は読まず、ログへ渡さない（`conventions.md:62`）。Spring Modulith 2.1.2 も、Listener の失敗ではメソッドと例外のメッセージだけを INFO で出し、イベントを出さない（`CompletionRegisteringAdvisor.java:228-238`）。

## ほかの Issue との関係

- **#378**：この調査の取り込むものを、コメントで受け入れ条件に足した。#378 はすでにログとトレースへのテナントの追加を持つので、同じ箇所（MDC、appender、`keep_keys`、ADR-015）を触る `user.id` も一緒にする。
- **#376**：テナントを束縛する入口（リクエストと Listener の Aspect）を作る。MDC はこの入口で入れる。#378 は #376 を待つ。
- **#320**（トレースの範囲と計装）：別のエージェントが調べている。span の属性にテナントを載せる方法（observation の key-value か、span の属性か）と、`user.id` を span に載せるかは #320 に任せる。この調査は baggage を使わないとしたので、#320 が baggage の `tag-fields` を選ぶとぶつかる。
- **#363 の Q24、Q27**：ログにはテナントの `public_id` を使い、ログの項目名は #321 の結果に合わせると決めている。この調査はその項目名を `tenant.id` に決めた。
- **見つけたこと（Issue は起こしていない）**：`log-output-points.md:30-55` は、要求時と応答時のアクセスログを求めるが、`backend/src/main` に実装がない（`http.route`、`http.server.request.duration` を出すコードがなく、`keep_keys` にもない）。実装するか、トレースの `http.server.requests` で足りるとして docs を直すかを決める必要がある。

## 確かめたこと

- 使っている版：Spring Boot 4.1.2、Logback 1.5.38、Micrometer Tracing 1.7.2、context-propagation 1.2.1、OpenTelemetry の Logback appender 2.33.0-alpha（`backend/build.gradle:16`、`:114`、Gradle のキャッシュ）。
- 最新版（Maven Central、2026-11-20 時点）：Spring Boot 4.2.0、Logback 1.6.8、Micrometer Tracing 1.8.0、context-propagation 1.2.1、appender 2.34.0-alpha。
- Spring Boot 4.2 のリリースノートの可観測性の変更（OpenTelemetry の semantic conventions、OTLP の共通の設定、MDC のキーの名前の設定 `management.tracing.mdc.trace-id-key`、OpenTelemetry での baggage の tag-fields）に、この判断を変えるものはない。上げずに 4.1.2 のまま実装できる。
- Logback 1.6 は 1.5 の置き換えで、Janino の条件式を外した。`logback-spring.xml` は条件式を使っていない。
- appender の `captureKeyValuePairAttributes`（`logback-spring.xml:10`）は 2.33.0-alpha で非推奨になり、起動時に警告を出す（`OpenTelemetryAppender.java:179-189`）。opentelemetry-java-instrumentation の main の CHANGELOG の Unreleased は、この設定を消し、key-value の取り込みを `structuredAttributesIncluded` に一本化し、既定ですべてを取るとしている。MDC の取り込みは別の設定のまま opt-in である。
  - その版に上げるときに要素を消せばよい。2.34.0-alpha のまま要素を消すと key-value を取らなくなるが、`ObservabilityContractTest` の key-value のテストが落ちるので気付ける。
  - ADR-015 の Alternative 4 の「appender の属性 allowlist は使っている版に存在しない」は、2.33.0-alpha の `keyValuePairAttributesIncluded` で成り立たなくなった。ただし Alternative 4 の残りの理由（ログ、トレース、標準出力で別々の仕組みが要る）は残るので、判断は変えない。#378 で ADR-015 を改めるときに、この一文も直す。
- 向こうの ADR 0049 の毎行の項目と三層のマスク（ADR 0049:15、:20）、`node-logger.ts`、`logger.ts`、`memory-destination.ts`、`apps/worker/src/log.ts`、`docs/logging/security.md`、`keys.md`、`web-api.md` を読んだ。
- 向こうの yokozi-jp/agent-software-factory#321 は開いたままで、`client.address` を出すかと、ECS と Lambda で `X-Forwarded-For` のどの位置を信頼するかを決めていない。

## 出典

- 向こう：`docs/adr/0049-logging-with-pino-behind-a-logger-interface.md`、`packages/logging/src/`、`docs/logging/`、`apps/worker/src/log.ts`、[yokozi-jp/agent-software-factory#321](https://github.com/yokozi-jp/agent-software-factory/issues/321)
- こちら：`docs/adr/ADR-015-structure-and-protect-observability-data.md`、`docs/observability/conventions.md`、`log-messages.md`、`log-output-points.md`、`log-operational-events.md`、`backend/src/main/resources/logback-spring.xml`、`application.yaml`、`docker/otel-collector/config.yaml`、`TenantSelectionFilter.java`、`AppUserPrincipal.java`、`ObservabilityContractTest.java`
- [Spring Boot: Structured Logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)
- [Spring Boot: Tracing（Logging Correlation IDs、Baggage）](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [Spring Boot 4.2 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)
- [Spring Boot: Customize Tomcat's Proxy Configuration](https://docs.spring.io/spring-boot/how-to/webserver.html)
- [Logback news](https://logback.qos.ch/news.html)（1.6.8、logback-scoped-mdc 1.0.0-rc0）
- [opentelemetry-java-instrumentation CHANGELOG](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/CHANGELOG.md)、`opentelemetry-logback-appender-1.0` 2.33.0-alpha と 2.34.0-alpha の sources jar
- Spring Boot 4.1.2 の sources jar（`ElasticCommonSchemaStructuredLogFormatter`）、Spring Modulith 2.1.2 の sources jar（`CompletionRegisteringAdvisor`）、`micrometer-observation-1.17.2.jar` の `META-INF/services`
- [OpenTelemetry semantic conventions: User](https://opentelemetry.io/docs/specs/semconv/registry/attributes/user/)、[semantic-conventions の model](https://github.com/open-telemetry/semantic-conventions/tree/main/model)
- [ECS: Organization fields](https://www.elastic.co/docs/reference/ecs/ecs-organization)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [ALB: Access log entries](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-access-logs.html)

## 作業の Issue

- #378（コメントで受け入れ条件を足した）
