---
type: ADR
title: 'ADR-019: 外部連携の耐障害性と容量制御を標準化する'
description: 外部の呼び出しを再試行する層をイベント出版の再投入だけにし、タイムアウト、circuit breaker、DB の接続数を決める、外部連携の耐障害性と容量制御の決定。
tags: [adr, resilience, backend, reliability]
---

# ADR-019: 外部連携の耐障害性と容量制御を標準化する

## Status

Proposed

## Date

2026-09-15

## Context

バックエンドは Resilience4j と仮想スレッドを導入済みだが、外部連携に適用する timeout、retry、circuit breaker の共通値を定めていない。

アプリケーションが所有する外部 HTTP クライアントは決済代行とメール送信で、PostgreSQL、OIDC の通信は各 Spring starter が管理している。
OIDC の呼び出しのうち、トークンの取得と UserInfo は既定でタイムアウトを持たない。
Resilience4j の設定を追加しても、これらの通信には自動適用されない。

retry をすべての呼び出しへ一律に適用すると、非冪等な更新や認証処理を重複実行する可能性がある。
HTTP クライアントと Resilience4j と上位の呼び出し元が別々に retry すると、試行回数が乗算されて依存先の障害を増幅する。

仮想スレッドは待機中の Java スレッドのコストを下げるが、PostgreSQL の接続数は増やさない。
したがって、HikariCP の pool が DB 処理の backpressure と接続予算を担う。

JDK 25 では JEP 491 により、通常の `synchronized` メソッドとブロックで待機する仮想スレッドは carrier thread を解放できる。
一方、native frame などによる pinning と、制限のない外部資源への同時実行は別の問題として残る。

この判断は、外部 client を infrastructure adapter に置く [ADR-002](ADR-002-package-by-feature-onion-architecture.md) と、環境差のある値を外部注入する [ADR-008](ADR-008-single-application-yaml-external-config.md) に従う。

[ADR-111](ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md) で Redis を外したため、Proposed の間にstarter が管理する通信から Redis を除いた。

Proposed の間に、外部の呼び出しを再試行する層を、Client の即時の再試行からイベント出版の再投入（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）の 1 つへ書き直した（[#573](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/573)）。
理由は次の 3 点である。

- 即時の再試行を使う経路がなかった。
  `payment-gateway` と `mail-sender` の retry は試行 1 回の `default` を継承し、`idempotent`（最大 3 回）を継承する instance はなかった。
  外部の呼び出しはすべて Listener の経路にあり（[#545](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/545)）、リスナーの方針が `abandon-after-attempts: 1` で 1 回目に諦めない限り、再投入が呼び直す。
- `default` の `max-attempts: 1` の 1 行を消すと、Resilience4j の既定（`RetryConfig.DEFAULT_MAX_ATTEMPTS` の 3 回）で黙って再試行する。
- Spring Framework 7 の `@Retryable` は、`@EnableResilientMethods` で有効にすると再投入と重なる（[Resilience Features](https://docs.spring.io/spring-framework/reference/core/resilience.html)）。

この書き直しで、Accepted の [ADR-072](ADR-072-fake-external-systems-with-wiremock.md) の Decision の次の 3 点を改める。
（1）`payment-gateway` の retry の instance の継承（67 行付近）、（2）`idempotent` の再試行の対象（68 行付近）、（3）WireMock の結合テストで確かめる対象からリトライを外す（43 行付近）。

## Decision

外部の呼び出しを再試行する層は、イベント出版の再投入（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）の 1 つだけにする。

- Client は即時の再試行をしない。
  Resilience4j の retry と、Spring Framework の `@Retryable` と `RetryTemplate` を使わない。
- SDK が自分で再試行する外部連携は、SDK の再試行を止める（メールは [ADR-125](ADR-125-send-mail-with-ses-v2-api-and-fake-it-with-wiremock.md)）。
- 一時障害（接続の失敗、タイムアウト、HTTP 429、HTTP 5xx）では、Client が例外を投げてイベント出版を `FAILED` に残し、再投入が回数の上限まで呼び直す。
  呼び出しの結果の分け方は [ADR-072](ADR-072-fake-external-systems-with-wiremock.md) に従う。
- 守らせる規則と検査は[外部システムの Client](../backend/class-roles/external-client.md) に置く。

即時の再試行は、同期の要求から外部システムを呼ぶ経路を足すときに決め直す。
その変更は、ArchUnit の `externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients` と `externalSystemCommandHandlersAreDependedOnlyByListeners` を緩める。
決め直すときは、即時の再試行に次の条件を付ける（姉妹リポジトリの [ADR 0023](https://github.com/yokozi-jp/agent-software-factory/blob/main/docs/adr/0023-ky-for-external-http-and-sdk-retries-for-aws.md)、AWS の Builders' Library の [Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)）。

- 冪等な操作（GET などの安全な操作と、相手が冪等性キーで重複を防ぐ操作）だけを再試行する。
- 待ちは、ジッター付きの指数的な待ちにする。
- `Retry-After` の待ちが呼び出し元の時間の予算に収まらないときは、再試行しない。
- 試行の回数と、待ちを含む合計の時間に上限を置く。
- 再試行の層は 1 つにし、HTTP クライアント、SDK、再投入の再試行と重ねない。

外部 HTTP client のタイムアウトは、HTTP クライアントの接続と呼び出しの 2 つに持つ。
開始値は次のとおりとする。

- 接続のタイムアウトは 1 秒とする。
- 一回の呼び出し全体のタイムアウトは 2 秒とする。
- Resilience4j の TimeLimiter は使わない。

外部連携ごとに名前付きの Resilience4j の circuit breaker の instance を定義し、`infrastructure.client` の Client のクラスかメソッドに付ける。
circuit breaker の既定値を、count based window 20 回、評価開始 10 回、failure rate 50 パーセント、open 30 秒、half-open 5 回とする。
外部システムを呼ぶリスナーが専用の executor で動き（[#685](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/685)）、ほかのリスナーと枠を共有しなくなったら、circuit breaker の要否を見直す。

Spring の starter が持つ通信は、Resilience4j で包まない。
今の対象は JDBC と HikariCP、Spring Security の OAuth2 Client で、Redis を戻すときの Lettuce も同じに扱う。
starter が持つ通信のタイムアウトは、呼び出しごとに次の値と置き場所で持つ。

- 認可コードをトークンに替える（ログインのコールバック）：接続 1 秒、応答 5 秒。`spring.http.clients.*` に置き、`OidcTokenClientConfig` が Boot の `RestClient.Builder` から作った `RestClient` を `RestClientAuthorizationCodeTokenResponseClient` に渡す。
- UserInfo：呼び出さない。`SignInOidcUserService` が使うのは ID トークンの `iss` と `sub` だけなので、`OidcUserService.setRetrieveUserInfo` で止める。UserInfo の HTTP クライアントは `new RestTemplate()` で、タイムアウトを足すには request factory を持つ `RestTemplate` が別に要る。
- ID トークンの JWK Set：接続 30 秒、応答 30 秒。Spring Security の `NimbusJwtDecoder` の既定を残す。上限があり、署名の鍵は読んだ後に cache される。変えるには `JwtDecoderFactory` の差し替えが要り、得るものより手間が大きい。
- 起動のときの discovery：接続 30 秒、応答 30 秒。Spring Security の `ClientRegistrations` の既定を残す。起動だけで、利用者を待たせない。`oidc.discovery-uri` を渡す E2E の `OidcDiscoveryLocationConfig` は `spring.http.clients.*`（1 秒、5 秒）に従う。
- PostgreSQL：接続は `DB_POOL_CONNECTION_TIMEOUT_MS`、応答は `DB_STATEMENT_TIMEOUT_MS`。HikariCP と `application.yaml` のセッションの `SET` に置く。セッションの読み書きも同じ。

トークンの取得の応答を、外部 HTTP client の開始値の 2 秒より長い 5 秒にする。ログインは利用者が画面で待つが、トランザクションの外にあり、IdP の一時的な遅延でログインを落とさないよう余裕を持たせる。
`spring.http.clients.*` は自分の値を持たない Boot の `RestClient` の既定なので、新しい client を足すときは自分の値で上書きする。決済代行は `payment-gateway.*` で上書きする。メールは AWS SDK の HTTP クライアントで、`spring.http.clients.*` を使わず `mail-sender.*` を使う。

外部連携を追加するときは、依存先の SLO と上位処理の時間予算から値を見直し、名前付きの circuit breaker の instance の設定と境界テストを同じ変更に含める。
既定値をそのまま採用した場合も、その根拠を client adapter の文書へ残す。

HikariCP の `maximum-pool-size` は、PostgreSQL がアプリケーションへ割り当てた接続数から算出して環境変数で注入する。
次の不等式を全環境で満たす。

``` text
maximum-pool-size × 最大アプリケーションタスク数
+ マイグレーション接続
+ 監視と運用の接続
+ 障害対応用の予約枠
<= PostgreSQL max_connections
```

ローカル開発の開始値は 10、テストは 4 とし、接続取得の待機上限は 5 秒とする。
`minimum-idle` は明示せず、HikariCP の固定サイズ pool の既定動作を使う。
本番値は負荷試験と RDS の接続予算で決め、ローカル値を流用しない。

仮想スレッドは有効のまま維持する。
`synchronized` を pinning 回避だけを理由に `ReentrantLock` へ置き換えない。
外部 client または native library を追加したときは、JFR を有効にした負荷スモークで pinning と HikariCP の接続待ち時間を確認する。

## Consequences

### Positive

- 非冪等処理の意図しない再実行を既定で防げる。
- 1 回の呼び出しの試行は 1 回なので、1 件の出版が外部を呼ぶ回数は再投入の回数の上限で決まる。
- retry storm と長時間の連鎖待ちを抑え、障害を呼び出し元へ返す時間を予測しやすくなる。
- DB 接続数を仮想スレッド数から切り離し、PostgreSQL の容量から逆算できる。
- Java 25 で解消済みの monitor pinning を理由に、不要な lock の書き換えを行わずに済む。

### Negative

- 一瞬の接続の失敗も、再投入の最初の待ち（`wait-age`）が過ぎるまで回復しない。
- 同期の要求から外部を呼ぶ経路を足すときに、即時の再試行を決め直す作業が要る。
- 外部連携ごとに例外分類、冪等性、時間予算を決める作業が必要になる。
- 小さすぎる timeout は正常だが遅い依存先を失敗扱いにし、大きすぎる pool は PostgreSQL を過負荷にする。
- circuit breaker の初期値は実トラフィックを測定した後に調整が必要になる。

### Neutral

- bulkhead と rate limiter は依存先の同時実行上限が判明した時点で、名前付き instance へ追加する。
- JFR の pinning 検証は常時有効化せず、負荷スモークと障害調査で実行する。

## Alternatives Considered

### すべての外部呼び出しを三回 retry する

- **Description**：共通の default retry を全 instance へ適用する。
- **Pros**：一時的な障害を追加設定なしで吸収できる。
- **Cons**：非冪等処理を重複実行し、複数層の retry が試行回数を乗算するため採用しない。

### Client に即時の再試行の設定を残し、同期の経路ができたら使う

- **Description**：`idempotent`（最大 3 回、200 ミリ秒から倍率 2 の待ち）と、試行 1 回の `default` の retry の設定を残す。
- **Pros**：同期の経路を足したときに、設定を継承するだけで再試行できる。
- **Cons**：使う経路がなく、ジッターも `Retry-After` の扱いもないため、決め直すときの条件を満たさず採用しない。

### circuit breaker も外す

- **Description**：姉妹リポジトリの ADR 0023 と AWS の Builders' Library に従い、circuit breaker を入れない。
- **Pros**：試験しにくい状態の切り替えがなくなり、回復が遅れることもない。
- **Cons**：全モジュールのリスナーが 2 枠の executor（`spring.task.execution.simple.concurrency-limit: 2`）を共有し、半死の決済代行が呼び出しごとに枠を最大 3 秒持つため、#685 で枠が分かれるまで採用しない。

### Spring Framework 7 の `@Retryable` で即時の再試行を持つ

- **Description**：Resilience4j の `@Retry` の代わりに、Spring Framework の `@Retryable` を Client に付ける。
- **Pros**：Resilience4j の retry の module が要らない。
- **Cons**：再投入と重なり、`@EnableResilientMethods` がないと黙って無視されるため採用しない。

### TimeLimiter で呼び出し全体を切る

- **Description**：Resilience4j の TimeLimiter に、呼び出し全体の上限を持たせる。
- **Pros**：HTTP クライアントの種類によらず、同じ設定で上限を置ける。
- **Cons**：`CompletionStage` を返すメソッドにしか効かず、上限が HTTP クライアントと 2 か所になるため採用しない。

### Resilience4j のライブラリ既定値だけを使う

- **Description**：設定を置かず、ライブラリの既定値へ委ねる。
- **Pros**：設定ファイルが短くなる。
- **Cons**：バージョン更新で実効値を見失いやすく、プロジェクトの時間予算と障害判定を共有できないため採用しない。

### HikariCP の pool を仮想スレッド数に合わせて増やす

- **Description**：同時リクエスト数に比例して DB 接続数を増やす。
- **Pros**：アプリケーション側の DB 待ち時間を短縮できる場合がある。
- **Cons**：PostgreSQL の接続上限と CPU を先に使い切り、全リクエストの遅延を悪化させるため採用しない。

## References

- [Resilience4j documentation](https://resilience4j.readme.io/docs/getting-started-3)
- [ADR 0023: 外部システムへの HTTP の呼び出しは ky でタイムアウトと再試行を持ち、AWS のサービスは SDK の再試行に任せて重ねない（yokozi-jp/agent-software-factory）](https://github.com/yokozi-jp/agent-software-factory/blob/main/docs/adr/0023-ky-for-external-http-and-sdk-retries-for-aws.md)
- [AWS Builders' Library: Timeouts, retries, and backoff with jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)
- [Spring Framework: Resilience Features](https://docs.spring.io/spring-framework/reference/core/resilience.html)
- [ADR-072: 外部システムを本番のコードで偽らず、WireMock のコンテナで偽る](ADR-072-fake-external-systems-with-wiremock.md)
- [ADR-079: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [ADR-125: メールを SES v2 の API で送り、ローカルとテストは接続先を WireMock に向ける](ADR-125-send-mail-with-ses-v2-api-and-fake-it-with-wiremock.md)
- [HikariCP: About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [OpenJDK JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [backend/src/main/resources/application.yaml](../../backend/src/main/resources/application.yaml)
