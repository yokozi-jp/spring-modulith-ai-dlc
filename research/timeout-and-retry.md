# タイムアウトと再試行を重ねない（#319 の調査）

この文書は #319 の調査の記録で、merge しない。
結論は #319 のコメントと同じで、作業は #573 と #574 で行う。
向こうは `yokozi-jp/agent-software-factory`（main、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `63e5c9f`）である。

## 結論

一部採用とする。
向こうの ADR 0023 の芯は「1 つの呼び出しで再試行する層は 1 つにし、SDK が自分で再試行するものを包まない」で、こちらの ADR-019 と ADR-079 も同じ方針である。
こちらでは外部の呼び出しがすべて Listener の経路にあり、再試行の層はイベント出版の再投入の 1 つで足りる。
そこで、次を取り込む。

- 再試行の層を再投入の 1 つに決め、使われない Client の即時の再試行（Resilience4j の `@Retry` と `idempotent`）と TimeLimiter を消す。即時の再試行を付ける注釈は ArchUnit で止める（#573）。
- 即時の再試行が要る経路を足すときの条件（冪等な操作だけ、ジッター、`Retry-After`、試行と待ちの合計の上限）を、ADR-019 に書いておく（#573）。
- 接続断で 1 回だけ呼ぶことを、受けた要求の回数で確かめる（#573）。
- すべての外部の呼び出しに、接続と呼び出しのタイムアウトを明示する。ログインの経路の IdP のトークンの取得と UserInfo にはタイムアウトがなく、Redis は Lettuce の既定（コマンド 60 秒）のままである（#574）。

circuit breaker を外すこと、HTTP クライアントのライブラリに再試行を持たせること、Spring Framework 7 の `@Retryable` へ移すことは取り込まない。
ADR-019 は Proposed なので、#573 と #574 で Decision を直接書き直す（`docs/adr/conventions.md:66`）。

## 向こうの決定（ADR 0023）

- 外部システム（AWS 以外）への HTTP は ky 2.2.0 で書き、タイムアウトと再試行をクライアントごとの ky の設定に置く（`docs/adr/0023-ky-for-external-http-and-sdk-retries-for-aws.md`、`packages/hr-system/src/infrastructure/client.ts:8-10`、`pnpm-workspace.yaml:88`）。
- 人事システムは、試行ごと 2 秒、全体 5 秒（`totalTimeout`）、GET だけ最大 2 回の再試行、タイムアウトでも再試行、ジッター付き。再試行する状態コードは ky の既定（408、413、429、500、502、503、504）で、429 と 503 は `Retry-After` に従う。
- サーキットブレーカーは入れない。理由は AWS の Builders' Library（状態の切り替えが試験しにくく、回復を遅らせうる。手元で再試行を絞る）。
- AWS のサービスは SDK の standard mode の再試行に任せ、外側で包まない。
- 1 つの呼び出しで再試行する層は 1 つ。ワーカーではメッセージの再配送が外側の層になり、その中の SDK と ky の再試行は既定のまま残し、3 つ目の層を足さない。
- WireMock の人事システムのフェイクで、タイムアウト、接続断、503 の後の 200、毎回の 503、404 の振る舞いを、受けた要求の回数まで確かめる。

## こちらの今

| 層                            | 持つもの                     | 値                                                                    | 出典                                                          |
| ----------------------------- | ---------------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------- |
| HTTP クライアント             | 接続と呼び出しのタイムアウト | 1 秒と 2 秒（決済代行）                                               | `application.yaml:224-231`、`PaymentGatewayClient.java:72-81` |
| Client の境界（Resilience4j） | 即時の再試行                 | `default` は試行 1 回、`idempotent` は最大 3 回、200 ミリ秒から倍率 2 | `application.yaml:251-272`                                    |
| Client の境界（Resilience4j） | circuit breaker              | 回数 20、評価開始 10、50%、open 30 秒、half-open 5 回                 | `application.yaml:236-250`                                    |
| Resilience4j                  | TimeLimiter                  | 2 秒                                                                  | `application.yaml:273-277`                                    |
| イベント出版の再投入          | 分から時間の単位の再試行     | 最初の待ち 5 分（本番想定）、倍率 2、最大 1 時間、5 回                | ADR-079、`application.yaml:189-221`                           |

決済の Client は `@CircuitBreaker` と `@Retry` を付け（`PaymentGatewayClient.java:88-89`）、retry の instance は `default`（試行 1 回）を継承する（`application.yaml:269-272`）。
ADR-079 の Neutral は、即時の再試行と再投入を別の層とし、`idempotent` を継承すると 6 × 3 = 18 回になると書く。

## 取り込むもの

### 再試行の層を再投入の 1 つにし、使われない即時の再試行と TimeLimiter を消す（#573）

外部システムのインタフェースは CommandHandler と Client からだけ使え、その CommandHandler は Listener からだけ呼べる（`ClassRoleArchTest.java:185-231` の `externalSystemCommandHandlersAreDependedOnlyByListeners` と `externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients`、#545）。
外部の呼び出しはすべて、再投入が外側にある Listener の経路にある。
そのため、次の 3 つは使われない。

- 決済の `@Retry(name = "payment-gateway")` は試行 1 回で、何もしない。
- `idempotent`（最大 3 回）を継承する instance はなく、上の規則のもとでは継承できる経路もない。
- TimeLimiter は `CompletionStage` を返すメソッドにしか効かず、使う箇所がない。`DemoApplicationTest.timeLimiterUsesProjectDefault` が値を確かめるだけである。

使われない仕組みは、事故の入口にもなる。
`@Retry` の名前に合う instance がないと Resilience4j は `configs.default` を使い、`configs.default.max-attempts: 1` を消すと既定の 3 回（`RetryConfig.DEFAULT_MAX_ATTEMPTS`、待ち 500 ミリ秒）で再試行する。
Client 以外に `@Retry` を付けても検査で止まらない（`ProxyRulesArchTest` は、注釈を付けたメソッドの自己呼び出しと修飾子だけを見て、付ける場所を見ない）。
Spring Framework 7 の `@Retryable` は、`@EnableResilientMethods` がないと黙って無視され、有効にすると再投入と重なる。

向こうの「層は 1 つ」をこちらの形で守るには、即時の再試行の仕組みを消して、付ける注釈を ArchUnit で止めるのが最も小さい。
即時の再試行が要るのは、同期の要求から外部を呼ぶ経路を足すとき（上の規則を緩めるとき）なので、そのときの条件を ADR-019 に書いておく。

- 冪等な操作だけ（ADR-019 と向こうの「GET だけ」）。
- ジッター付きの指数の待ち。今の `idempotent` にはジッターがない。Builders' Library は、待ちがそろうと再試行がまた重なるとしてジッターを勧め、向こうは ky の既定にジッターがないので必ず明示する。
- `Retry-After`。Resilience4j の retry には従う設定がない。今の `idempotent` は 429 を 200 ミリ秒と 400 ミリ秒の後に再試行する。待ちが時間の予算に収まらないなら再試行しない。
- 試行と待ちの合計の上限。ky の `totalTimeout` に当たる。こちらは `DemoApplicationTest.paymentGatewayWorstCaseFitsIdleInTransactionTimeout` が、最悪の時間が `idle_in_transaction_session_timeout` より短いことを確かめている。

### 接続断で 1 回だけ呼ぶことを確かめる（#573）

`PaymentGatewayClientIntegrationTest` は、5xx とタイムアウトで決済代行が 1 回だけ呼ばれることを、WireMock の受けた要求の回数で確かめる（`assertFailedOnce`、`PaymentGatewayClientIntegrationTest.java:419-430`）。
接続断（接続のリセット）の場合はない。
向こうのテストの観点のうち、こちらに欠けているのはこれだけである。
503 の後の 200 は、試行 1 回のこちらでは起きない。404 は契約の不備の 4xx として確かめている。

### すべての外部の呼び出しにタイムアウトを明示する（#574）

向こうは「外部システムのクライアントを足すたびに、タイムアウトと再試行の値を決める」とする。
Builders' Library も、すべてのリモートの呼び出しに接続と要求の両方のタイムアウトを置くことを勧める。
こちらの ADR-019 は「PostgreSQL、Redis、OIDC 通信は各 starter の timeout と再接続設定を使う」とする（Neutral）が、OIDC と Redis には明示したタイムアウトがない。

| 呼び出し                                               | 実装                                                                                                                                                                                                                                                                                                 | 今のタイムアウト                                                                                    |
| ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| 認可コードをトークンに替える（ログインのコールバック） | `AbstractRestClientOAuth2AccessTokenResponseClient.java:65` の `RestClient.builder()`。classpath に Apache HttpClient、Jetty、Reactor Netty がないので `JdkClientHttpRequestFactory`（`DefaultRestClientBuilder.initRequestFactory`）。既定の `HttpClient.newHttpClient()` と、`readTimeout` が null | なし                                                                                                |
| UserInfo を読む（ログインのコールバック）              | `DefaultOAuth2UserService.java:84-87` の `new RestTemplate()`（`SimpleClientHttpRequestFactory`、タイムアウト -1）                                                                                                                                                                                   | なし                                                                                                |
| ID トークンの JWK Set を読む                           | `NimbusJwtDecoder` の `RestTemplateWithDefaultTimeouts`（`JwtDecoderProviderConfigurationUtils.java:88-99`）                                                                                                                                                                                         | 接続 30 秒、読み 30 秒                                                                              |
| discovery を読む（起動のとき）                         | `ClientRegistrations.java:63-70`                                                                                                                                                                                                                                                                     | 30 秒                                                                                               |
| Redis                                                  | Lettuce。`spring.data.redis.timeout` と `connect-timeout` が未設定（`application.yaml:89-93`）                                                                                                                                                                                                       | コマンド 60 秒（`RedisURI.DEFAULT_TIMEOUT`）、接続 10 秒（`SocketOptions.DEFAULT_CONNECT_TIMEOUT`） |

Spring Boot 4.1.2 の `spring-boot-security-oauth2-client` は、トークンの取得と UserInfo の HTTP クライアントを構成しない（sources の jar に `TokenResponseClient`、`RestClient`、`OAuth2UserService` を参照するクラスがない）。
IdP が応答しないと、ログインのコールバックの要求が止まったまま残る。
`SignInOidcUserService` が使うのは ID トークンの `iss` と `sub` だけなので（`SignInOidcUserService.java:60-61`）、UserInfo は読まずに済む可能性がある（`OidcUserService.setRetrieveUserInfo`）。
トークンの取得は `RestClientAuthorizationCodeTokenResponseClient.setRestClient` で、Boot の `RestClient.Builder` と `HttpClientSettings` から作った `RestClient` を渡せる。
Spring Security 7.2 の `DefaultOAuth2UserService` は `RestOperations` しか受け取らない（`setRestOperations`）。

## 取り込まないもの

- **サーキットブレーカーを外す**：向こうは Builders' Library に従って入れない。
  Builders' Library の懸念は、層ごとの再試行が掛け算で負荷を増やすことと、circuit breaker の状態の切り替えが試験しにくいことである。
  こちらは再試行の層が 1 つで、再投入は分の単位で待つので、前者は起きない。
  一方、こちらでは全モジュールのリスナーが `applicationTaskExecutor` の 2 枠を共有する（`application.yaml` の `spring.task.execution.simple.concurrency-limit`、#557）。
  半死の決済代行は、枠を 1 回あたり最大 3 秒（接続 1 秒と呼び出し 2 秒）持ち、ほかのモジュールのリスナーを待たせる。
  circuit breaker は、open の間これを止める。
  `docs/nfr/fault-tolerance.md:65` も、半死を circuit breaker で切り離すとしている。
  状態の切り替えは `PaymentGatewayClientIntegrationTest` が確かめている。
  #557 の結論で遅いリスナーの枠が分かれたら、circuit breaker の要否を見直す（#573 の ADR-019 に書く）。
- **HTTP クライアントのライブラリに再試行を持たせる（ky の形）**：JDK の `HttpClient` と Spring の `RestClient` は再試行を持たない。こちらは即時の再試行そのものをやめるので、置き場所の選び直しは要らない。
- **Spring Framework 7 の `@Retryable` と `RetryTemplate` へ移す**：Spring Framework 7.0 から `spring-core` に `RetryTemplate` と `RetryPolicy`、`spring-context` に `@Retryable` と `@ConcurrencyLimit` がある（7.0 のリリースノート）。7.0.2 からは、試行と待ちの合計の上限（`timeout`）も持つ。ただし、上限は試行の合間にだけ判定し、実行中の試行は止めない（`RetryTemplate.java:147-157`、`checkIfTimeoutExceeded`）。ジッター（`jitter`）もある。即時の再試行をやめるので、今は使う場所がない。同期の経路を足して即時の再試行を決め直すときの候補にする。
- **Resilience4j の retry にジッターを足す**：`enable-randomized-wait` と `randomized-wait-factor` で足せる（`CommonRetryConfigurationProperties.configureRetryIntervalFunction`）が、`idempotent` ごと消すので足さない。
- **AWS SDK の retry mode の扱い**：こちらのアプリは AWS SDK を使わない。「SDK や driver が自分で再試行や再接続をするものを包まない」という原則だけを、JDBC と HikariCP、Lettuce、Spring Security の OAuth2 Client に当てて ADR-019 に書く（#573）。
- **全体のタイムアウトを別に持つ（ky の `totalTimeout`）**：即時の再試行がないので、1 回の試行のタイムアウトが全体のタイムアウトになる。Spring の `JdkClientHttpRequest` は、呼び出しのタイムアウト（`read-timeout`）を送信から本文を読み終えるまでに掛ける（`JdkClientHttpRequest.java:115-122`、`TimeoutHandler`）。

## ほかの Issue との関係

- **#557（遅いリスナーと executor の枠）**：circuit breaker を残す理由は共有の枠である。#557 で枠を分けたら、circuit breaker の要否を見直す。1 回の呼び出しが枠を持つ時間は、試行 1 回のまま最大 3 秒である。
- **#562（Spring Modulith 2.2、Boot 4.2）**：Spring Cloud の 2025.1.x は Boot 4.0.x と 4.1.x 向けで、Boot 4.2 へ上げると train も上げる必要がある。#573 で Spring Cloud をやめ、`resilience4j-spring-boot4` を直接入れると、この結びつきがなくなる。circuit breaker を残すので、open の間の再投入が外部を呼ばずに回数を消費する扱い（ADR-079 の Negative）は残る。#562 で `AbandonPolicy` を決めるときは、`CallNotPermittedException` をすぐ諦める例外に入れない。
- **#559（DLQ と Runbook）**：Runbook の `OrderConfirmedListener` の行（接続の失敗、タイムアウト、429、5xx、`CallNotPermittedException`）は、#573 の後も変わらない。
- **#558（Spring Batch）**：chunk の retry と skip は Spring Batch の層である。今の ArchUnit の規則では、外部システムを呼ぶ CommandHandler を Listener 以外から呼べない。バッチから外部を呼ぶ形を足すときは、Client で即時の再試行をしない原則のまま、chunk の retry を唯一の層にする。
- **#363、#316（テナントと IdP）**：#574 はログインの経路の HTTP クライアントだけを変え、IdP と所属の扱いは変えない。
- **#341（外部システムの応答を自分の型に写す層）**：Client の形（`RestClient` か HTTP Service Client か）は #341 で扱う。Spring Boot 4 の HTTP Service Client なら、`spring.http.serviceclient.<group>.connect-timeout` と `read-timeout` でタイムアウトを設定で持てる（Boot のリファレンス）。

## 確かめたこと

- 依存の版：`./gradlew dependencies --configuration runtimeClasspath` で、`spring-core:7.0.10`、`spring-security-oauth2-client:7.1.2`、`lettuce-core:7.5.2.RELEASE`、`resilience4j-*:2.3.0`（`resilience4j-spring-boot3` を含む）、`spring-cloud-circuitbreaker-resilience4j:5.0.2`、`spring-cloud-context:5.0.2` が入る。`httpclient5`、`jetty-client`、`reactor-netty-http` は入らない。
- 最新版（2026-11-21 に Maven Central で確認）：Spring Boot 4.2.0、Spring Framework 7.1.0、Spring Security 7.2.0、Resilience4j 2.4.0（`resilience4j-spring-boot4` あり）、Spring Cloud 2026.0.0（`spring-cloud-circuitbreaker-resilience4j:5.1.0` は `resilience4j-spring-boot4:2.4.0` を使う）。
- Spring Framework 7.1 のリリースノートに、再試行と HTTP クライアントの判断を変える変更はない。HTTP クライアントのタイムアウトの `0` を「上限なし」にそろえ、負の値を拒否するようになった。`RestTemplate` は 7.1 で非推奨になった。
- Spring Boot 4.2 のリリースノートの再試行の変更は、`RetryPolicySettings` で全体のタイムアウトを設定できることだけである（Boot が構成する再試行の設定で、`@Retryable` とは別）。`RestTemplateBuilder` は 4.2.0 で非推奨になった。
- Spring Framework 7.1.0 の `@Retryable` は `includes`、`excludes`、`maxRetries`、`delay`、`jitter`、`multiplier`、`maxDelay`、`predicate`、`timeout`（7.0.2 から）を持つ（Javadoc）。
- Spring Security 7.1.2 の sources の jar で、トークンの取得、UserInfo、JWK Set、discovery の HTTP クライアントの作り方を読んだ（上の表）。Spring Boot 4.1.2 の `spring-boot-security-oauth2-client` の sources の jar に、これらを構成するクラスはない。
- 向こうの `packages/hr-system/src/infrastructure/client.ts:1-40` で、ADR 0023 の値がコードと同じであることを確かめた。
- 動かして確かめたものはない。タイムアウトがないことは、上のソースコードの読みに基づく。#574 のテストで実際の振る舞いを確かめる。

## 作業の Issue

- #573 refactor(backend): 外部呼び出しの再試行を再投入の 1 層にし、使わない即時の再試行と TimeLimiter を消す
- #574 fix(backend): IdP のトークンの取得と UserInfo、Redis にタイムアウトを明示する

## 出典

- 向こう：`docs/adr/0023-ky-for-external-http-and-sdk-retries-for-aws.md`、`packages/hr-system/src/infrastructure/client.ts`、`pnpm-workspace.yaml:88`（commit `29ba897`）
- こちら：ADR-019、ADR-055、ADR-072、ADR-079、`docs/backend/class-roles/external-client.md`、`docs/nfr/fault-tolerance.md`、`backend/src/main/resources/application.yaml`、`PaymentGatewayClient.java`、`SignInOidcUserService.java`、`DemoApplicationTest.java`、`PaymentGatewayClientIntegrationTest.java`、`ClassRoleArchTest.java`（commit `63e5c9f`）
- [AWS Builders' Library: Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)
- [Spring Framework リファレンス: Resilience Features](https://docs.spring.io/spring-framework/reference/core/resilience.html)
- [Spring Framework 7.1.0 Javadoc: `@Retryable`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/resilience/annotation/Retryable.html)、[`RetryPolicy.Builder`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/retry/RetryPolicy.Builder.html)
- [Spring Framework `RetryTemplate.java`（v7.1.0）](https://github.com/spring-projects/spring-framework/blob/v7.1.0/spring-core/src/main/java/org/springframework/core/retry/RetryTemplate.java)、[`JdkClientHttpRequest.java`（v7.1.0）](https://github.com/spring-projects/spring-framework/blob/v7.1.0/spring-web/src/main/java/org/springframework/http/client/JdkClientHttpRequest.java)、[`DefaultRestClientBuilder.java`（v7.1.0）](https://github.com/spring-projects/spring-framework/blob/v7.1.0/spring-web/src/main/java/org/springframework/web/client/DefaultRestClientBuilder.java)
- [Spring Framework 7.0 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes)、[Spring Framework 7.1 Release Notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.1-Release-Notes)
- [Spring Boot 4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)、[Spring Boot 4.2 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)
- [Spring Boot リファレンス: HTTP Service Interface Clients](https://docs.spring.io/spring-boot/reference/io/rest-client.html)
- [Spring Security Javadoc: `DefaultOAuth2UserService`](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/client/userinfo/DefaultOAuth2UserService.html)、[`RestClientAuthorizationCodeTokenResponseClient`](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/client/endpoint/RestClientAuthorizationCodeTokenResponseClient.html)
- [Spring Cloud: Release train Spring Boot compatibility](https://spring.io/projects/spring-cloud)
- [Resilience4j v2.4.0 リリース](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0)、[`CommonRetryConfigurationProperties.java`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-framework-common/src/main/java/io/github/resilience4j/common/retry/configuration/CommonRetryConfigurationProperties.java)、[`RetryConfig.java`](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-retry/src/main/java/io/github/resilience4j/retry/RetryConfig.java)
- [Lettuce `RedisURI.java`](https://github.com/redis/lettuce/blob/main/src/main/java/io/lettuce/core/RedisURI.java)、[`SocketOptions.java`](https://github.com/redis/lettuce/blob/main/src/main/java/io/lettuce/core/SocketOptions.java)
