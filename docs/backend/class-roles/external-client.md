---
type: Convention
title: クラスの役割：外部システムの Client
description: infrastructure.client に置き、外部システムのインタフェースを HTTP で実装する Client の定義、置き場所と命名、必須の記述（タイムアウトと名前付きの Resilience4j の instance）、呼び出しの結果の分類、依存、例、テスト（JDK の HttpServer と WireMock）、アンチパターン、作成時のチェックリストを定める。外部システムの HTTP API を呼ぶ実装を作るときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：外部システムの Client

`<ExternalSystem>Client` は、`domain.model` の外部システムのインタフェースを HTTP で実装するクラスであり、`infrastructure.client` に package-private で置いて `@Component` を付ける。
接続先の URL は設定から受け取り、タイムアウトを設定した `RestClient` で呼ぶ。
名前付きの Resilience4j の instance を、この層のメソッドにだけ付ける。
呼び出しの結果は四つに分け、再投入で回復できる失敗だけを例外で投げる。
本番のコードに外部システムの偽物を置かず、ローカルとテストでは WireMock が外部システムを偽る。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に、偽物と結果の分類の決定理由は [ADR-072](../../adr/ADR-072-fake-external-systems-with-wiremock.md) に示す。

## 定義

Listener から呼ばれる CommandHandler は、決済のような外部システムを[外部システムのインタフェース](external-system-interface.md)で呼び、HTTP の詳細を知らない。
**外部システムの Client**（`<ExternalSystem>Client`）は、そのインタフェースを、外部システムの HTTP API の呼び出しで実装するクラスである。
URL、JSON の形、タイムアウト、サーキットブレーカー、リトライは、Client だけが扱う。

Client は業務規則を持たない。
外部システムの応答を Domain の型に変換して返すだけにする。
本番のコードには実際の HTTP の Client だけを置き、偽物の分岐や mode の設定を持たない。

Client はユースケースの進行役でもない。
いつ呼ぶかは、インタフェースを通して呼ぶ [CommandHandler](command-handler.md) が決める。

## 置き場所と命名

- `com.example.demo.<feature>.infrastructure.client` に置く。
- 名前は外部システムのインタフェースの名前に `Client` を付ける（`PaymentGateway` の実装は `PaymentGatewayClient`）。
- Resilience4j の instance の名前と設定のキーは、外部システムの名前の kebab-case にする（`payment-gateway`、`payment-gateway.base-url`）。
- 外部 API の JSON の形は、Client にネストした private の record にする（`ChargeBody`、`ChargeReply`）。
  名前を `Request` や `Response` で終えない。

## 必須の記述

- `@Component` を付けた package-private の `class` にし、`final` を付けず、外部システムのインタフェースを実装する。
- コンストラクタは一つにし、`@Value("${payment-gateway.base-url}") final String baseUrl` と、`@Value` の `Duration` の `connect-timeout` と `read-timeout` を受け取る。
  `application.yaml` の `base-url` は既定値のない環境変数（`${PAYMENT_GATEWAY_BASE_URL}`）にし、未設定なら起動に失敗させる。
  WireMock の URL は env の例と compose ファイルにだけ書く。
- コンストラクタで、`JdkClientHttpRequestFactory` に接続のタイムアウト（1 秒）と呼び出しのタイムアウト（2 秒）を設定し、`RestClient` を作る。
  値は `application.yaml` の `payment-gateway` に固定値で書き、テストが同じ値を読めるようにする。
- トランザクションの中で呼ぶ Client は、最悪の時間を `idle_in_transaction_session_timeout` より短くする。
  [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) により、Listener は CommandHandler のトランザクションの中で外部システムを呼ぶ。
  最悪の時間は、(接続のタイムアウト + 呼び出しのタイムアウト) × retry の試行の回数 + 再試行の待ちの合計である。
  これが `DB_IDLE_IN_TRANSACTION_TIMEOUT_MS`（[ADR-055](../../adr/ADR-055-set-db-time-limits-per-connection.md)）以上だと、待つ間に PostgreSQL が接続を切り、結果を記録できない。
  今の値は 1 × (1 秒 + 2 秒) + 0 = 3 秒で、ローカルとテストの 10 秒より短い。
  `DemoApplicationTest` が束縛した設定からこれを確かめる。
- 実装するメソッドに `@CircuitBreaker(name = "payment-gateway")` と `@Retry(name = "payment-gateway")` を付ける。
  リトライはこの層だけで行い、CommandHandler や HTTP クライアントで重ねない。
- instance の設定は、`application.yaml` の `resilience4j` に、既定の設定を継承して書く。
  冪等性キーを渡さない更新の操作は `retry` の `default` を、GET などの冪等な操作は `idempotent` を継承する。
  `charge` は冪等性キーで重複を防げるが、`ChargeOrderCommandHandler` のトランザクションの中で呼ぶため、`default` を継承して試行を一回にし、失敗した請求はイベント出版の再投入でやり直す。
- 冪等性キーを受け取る操作は、キーを外部システムの API が定めるヘッダー（決済システムの例では `Idempotency-Key`）で送る。
- 外部システムの応答は、Domain の型（`ChargeOutcome`、`GatewayPaymentCode`）に変換して返す。
- 応答は `toEntity` で状態のコードと本文を受け取り、契約に合うことを確かめてから変換する。
  決済代行の契約では、状態のコードが `201` で、JSON の本文に空でない `chargeId` と既知の `status`（`SUCCEEDED` か `DECLINED`）があることを確かめる。
  合わない応答は例外にせず、契約の不備の結果にする。
- 呼び出しの結果は、次の節の四つに分けて扱う。
- クラス、定数、フィールド、コンストラクタ、実装するメソッドに Javadoc を書く。
- `infrastructure.client` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

タイムアウト、リトライ、サーキットブレーカーの値は [ADR-019](../../adr/ADR-019-define-resilience-and-capacity-guardrails.md) に従う。

```yaml
payment-gateway:
  base-url: ${PAYMENT_GATEWAY_BASE_URL}
  connect-timeout: 1s
  read-timeout: 2s

resilience4j:
  circuitbreaker:
    instances:
      payment-gateway:
        base-config: default
        ignore-exception-predicate: com.example.demo.payment.infrastructure.client.IgnoredClientErrorPredicate
  retry:
    configs:
      idempotent:
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpClientErrorException$TooManyRequests
          - org.springframework.web.client.HttpServerErrorException$BadGateway
          - org.springframework.web.client.HttpServerErrorException$ServiceUnavailable
          - org.springframework.web.client.HttpServerErrorException$GatewayTimeout
    instances:
      payment-gateway:
        base-config: default
```

## 呼び出しの結果の分類

Client は、外部システムの呼び出しの結果を次の四つに分ける。
再投入で回復できる結果だけを例外にしてイベント出版の `FAILED` に残し、回復しない結果は呼び出し元が業務の状態に記録する。
回復不能なエラーの扱いは[非同期処理の失敗時の再試行と回復](../../integration/async-failure-recovery.md)に従う。

| 分類           | 例                                           | Client               | circuit breaker | イベント出版 |
| -------------- | -------------------------------------------- | -------------------- | --------------- | ------------ |
| 一時障害       | 接続の失敗、タイムアウト、429、5xx           | 例外を投げる         | 失敗に数える    | `FAILED`     |
| 業務上の拒否   | カードの拒否（`201` の `status: DECLINED`）  | 拒否を表す結果を返す | 数えない        | `COMPLETED`  |
| 資格情報の不備 | 401、403                                     | 例外を投げる         | 数えない        | `FAILED`     |
| 契約の不備     | 401、403、429 以外の 4xx、契約に合わない応答 | 失敗を表す結果を返す | 数えない        | `COMPLETED`  |

- 結果は `domain.model` の型（`ChargeOutcome` と、`PAID`、`DECLINED`、`FAILED` の `PaymentStatus`）で返す。
  CommandHandler は結果を集約に記録して正常に返し、リスナーを正常終了させる。
- 契約の不備は、Client が `HttpClientErrorException` を捕まえて失敗の結果に変え、状態のコードを WARN のログに残す。
- 契約に合わない応答は、`201` 以外の 2xx と 3xx、本文がない、JSON として読めない（`UnknownContentTypeException`、原因が `HttpMessageNotReadableException`）、`status` が未知かない、`chargeId` がないか空白だけの応答である。
  同じ要求を再投入しても直らないため、Client は失敗の結果に変え、状態のコードと `status` を WARN のログに残し、本文はログに出さない。
  `IllegalStateException` を投げると、どの分類にも入らない失敗が出版の `FAILED` に残り続けるため投げない。
- circuit breaker の instance は、429 以外の `HttpClientErrorException` を無視する述語を `ignore-exception-predicate` に指定する。
  Resilience4j は述語を引数のないコンストラクタで作るため、述語のクラスは `public` にする。
- `ignore-exceptions` に `HttpClientErrorException` を書かない。
  一時障害の 429 まで無視し、型の列挙では 429 だけを失敗に数えられないためである。
- `idempotent` の retry は、`retry-exceptions` に [ADR-019](../../adr/ADR-019-define-resilience-and-capacity-guardrails.md) の一時障害（接続の失敗とタイムアウト、429、502、503、504）だけを書き、継承する instance はこれを使う。
  `default`（試行 1 回）を継承する instance は、どの結果も再試行しない。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じモジュールの `domain.model` の外部システムのインタフェースと値オブジェクト、Spring の `RestClient`、`JdkClientHttpRequestFactory`、`HttpClientErrorException`、`HttpStatus`、`@Component`、`@Value`、Resilience4j の `@CircuitBreaker` と `@Retry`、Lombok の `@Slf4j`。
- **依存してはいけない型**：`application`、`domain.service`、`presentation.web`、`infrastructure.persistence` の型、jOOQ の API と生成型、モジュールルートの型、他モジュールの型、`@Service`、`@Transactional`。

## 最小の例と典型的な例

最小の例は、決済システムに代金を請求する `PaymentGatewayClient` である。
成功の応答だけを受付にし、ほかの応答は契約の不備にする。
4xx と 5xx の分類とログは省いている。

```java
package com.example.demo.payment.infrastructure.client;

/** 決済システムの HTTP API で、注文 ID を冪等性キーにして注文の代金を請求する。 */
@Component
class PaymentGatewayClient implements PaymentGateway {

  /** 決済システムが冪等性キーを受け取るヘッダー。 */
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** 決済システムを呼ぶ HTTP クライアント。 */
  private final RestClient restClient;

  /** 決済システムの URL とタイムアウトを受け取り、HTTP クライアントを作る。 */
  /* package */ PaymentGatewayClient(
      @Value("${payment-gateway.base-url}") final String baseUrl,
      @Value("${payment-gateway.connect-timeout}") final Duration connectTimeout,
      @Value("${payment-gateway.read-timeout}") final Duration readTimeout) {
    final JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
    requestFactory.setReadTimeout(readTimeout);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  /** 注文の代金を請求し、結果を返す。 */
  @CircuitBreaker(name = "payment-gateway")
  @Retry(name = "payment-gateway")
  @Override
  public ChargeOutcome charge(final OrderId orderId, final Money amount) {
    final ResponseEntity<ChargeReply> response =
        restClient
            .post()
            .uri("/v1/charges")
            .header(IDEMPOTENCY_KEY, orderId.value().toString())
            .body(new ChargeBody(orderId.value().toString(), amount.amount()))
            .retrieve()
            .toEntity(ChargeReply.class);
    final ChargeReply reply = response.getBody();
    if (response.getStatusCode().value() != HttpStatus.CREATED.value()
        || reply == null
        || reply.chargeId() == null
        || !"SUCCEEDED".equals(reply.status())) {
      return ChargeOutcome.failed();
    }
    return ChargeOutcome.paid(new GatewayPaymentCode(reply.chargeId()));
  }

  /** 請求の API に送る本文。 */
  private record ChargeBody(String orderId, BigDecimal amount) {}

  /** 請求の API が返す本文。 */
  private record ChargeReply(@Nullable String chargeId, @Nullable String status) {}
}
```

典型的な例は、`payment` モジュールの `PaymentGatewayClient` が結果を分類し、`ChargeOrderCommandHandler` がインタフェースを通してその結果を記録する場面である。
CommandHandler は `PaymentGateway` に依存し、`PaymentGatewayClient` と HTTP の例外を知らない。

```java
// com.example.demo.payment.infrastructure.client.PaymentGatewayClient（抜粋）
private static ChargeOutcome contractError(
    final String key, final HttpClientErrorException exception) {
  final int status = exception.getStatusCode().value();
  if (status == HttpStatus.UNAUTHORIZED.value()
      || status == HttpStatus.FORBIDDEN.value()
      || status == HttpStatus.TOO_MANY_REQUESTS.value()) {
    throw exception;
  }
  log.warn(
      "Payment gateway rejected the charge request as a contract error: orderId={}, status={}",
      key,
      status);
  return ChargeOutcome.failed();
}

// toOutcome（抜粋）
if (httpStatus != HttpStatus.CREATED.value() || reply == null) {
  return contractViolation(key, httpStatus, reply == null ? null : reply.status());
}
// chargeId の検査（略）
if (SUCCEEDED.equals(reply.status())) {
  return ChargeOutcome.paid(code);
}
if (DECLINED.equals(reply.status())) {
  return ChargeOutcome.declined(code);
}
return contractViolation(key, httpStatus, reply.status());
```

```java
// com.example.demo.payment.application.ChargeOrderCommandHandler（抜粋）
final ChargeOutcome outcome = paymentGateway.charge(orderId, amount);
final Payment payment = Payment.record(orderId, amount, outcome, Instant.now(clock));
paymentRepository.add(payment);
```

## 対応するテスト

Client の単体テストは JDK の `HttpServer` で、リトライ、circuit breaker、イベント出版を通す結合テストと E2E は WireMock で書く。

### Client の単体テスト

Spring を起動しない JUnit のテストで、JDK の `com.sun.net.httpserver.HttpServer` を空いているポートで起動し、Client に URL を渡す。
HTTP の本文と Domain の型の変換を確かめ、応答を遅らせたときにタイムアウトすることも同じ方法で確かめる。
冪等性キーのヘッダーも同じ方法で確かめる。
結果の分類（拒否、契約の不備の 4xx、契約に合わない応答、例外を投げる 401、403、429）も同じ方法で確かめる。
Spring を起動しないため、このテストでは `@CircuitBreaker` と `@Retry` は働かない。

```java
/** 決済システムの Client の HTTP の呼び出しを検証する。 */
class PaymentGatewayClientTest {

  @Test
  @DisplayName("注文 ID を冪等性キーにして請求し、決済システムが返した識別子付きの受付を返す")
  void chargesWithOrderIdAsIdempotencyKey() throws IOException {
    final UUID orderUuid = UUID.fromString("00000000-0000-4000-8000-000000000001");
    final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/charges",
        exchange -> {
          idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          final byte[] body = "{\"chargeId\":\"ch_1\",\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(201, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      final PaymentGatewayClient client =
          new PaymentGatewayClient(
              "http://localhost:" + server.getAddress().getPort(),
              Duration.ofSeconds(1),
              Duration.ofSeconds(2));

      final ChargeOutcome outcome =
          client.charge(new OrderId(orderUuid), new Money(new BigDecimal("1000")));

      assertThat(outcome)
          .as("orderId の請求の結果")
          .isEqualTo(ChargeOutcome.paid(new GatewayPaymentCode("ch_1")));
      assertThat(idempotencyKey).as("orderId の請求の冪等性キー").hasValue(orderUuid.toString());
    } finally {
      server.stop(0);
    }
  }
}
```

### WireMock の結合テスト

リトライ、circuit breaker、イベント出版の状態は、`@ApplicationModuleTest` の中で Client を WireMock（`org.wiremock:wiremock-standalone`、`testImplementation` だけ）に向けて確かめる。
例は `PaymentGatewayClientIntegrationTest` である。

- `WireMockServer` を static のフィールドの初期化で起動し、`@AfterAll` で止める。
  `@RegisterExtension` の `WireMockExtension` は、Spring のコンテキストが `@DynamicPropertySource` で URL を読む時点でまだサーバを起動していないため使わない。
- `@DynamicPropertySource` で `payment-gateway.base-url` を WireMock の URL に向ける。
- `http2PlainDisabled(true)` を指定する。
  JDK の `HttpClient` は平文の HTTP で HTTP/2 への upgrade（h2c）を試み、WireMock（Jetty）では本文付きの POST が切れるためである。
- `usingFilesUnderDirectory("../docker/wiremock")` で、compose の WireMock と同じ成功のスタブを読む。
  Gradle の `test` タスクは `docker/wiremock` を入力（`inputs.dir`）に宣言しているため、スタブを変えるとテストがやり直される。
- 失敗と拒否は、冪等性キーに一致する注文ごとのスタブを `atPriority(1)` で共有のスタブより優先して足す。
  `@BeforeEach` で `resetToDefaultMappings()` と `resetRequests()` を呼び、circuit breaker を `reset()` する。
- 四つの分類ごとに、公開の境界（注文の確定から始まるイベント）から出版の状態（`COMPLETED` か `FAILED`）、決済記録、circuit breaker の失敗の件数を確かめる。
- テストの依存の jar の版は、compose の WireMock のイメージのタグと同じにする。

## アンチパターン

- CommandHandler や Domain Service で `RestClient` を直接使う。
- タイムアウトを設定せず、TimeLimiter だけに中断を任せる。
- CommandHandler に `@Retry` を付ける、または HTTP クライアントの再試行と重ねる。
  一回の失敗で試行の回数が掛け算で増える。
- 冪等性キーを渡さない更新の操作に `idempotent` を継承させて再試行する。
  一回の更新が二重に実行されうる。
- 冪等性キーを外部システムへ送らない。
  ロールバックやイベント出版の再投入で `charge` をもう一度呼ぶと、二重に請求する。
- 外部 API の JSON の record を `domain.model` や `application` に置く、またはインタフェースの戻り値にする。
- URL をコードに直接書く、または `base-url` に既定値を置く。
- 本番のコードに外部システムの偽物を置き、mode の設定で成功と失敗を切り替える。
- `ignore-exceptions` に `HttpClientErrorException` を書き、一時障害の 429 まで circuit breaker の失敗に数えない。
- 業務上の拒否や契約の不備を例外にして、再投入しても回復しない出版を `FAILED` に積む。
- Spring の結合テストで `WireMockExtension` を `@RegisterExtension` で登録し、`@DynamicPropertySource` から URL を読む。
- `@Service` を付ける、または名前を `Adapter` で終える。
- `application` の CommandHandler や QueryService、`domain.service`、モジュールルートの型を使う。
  Presentation のほかに処理の入口ができ、トランザクション境界が Application の外にも広がる。

## 作成時のチェックリスト

- [ ] `infrastructure.client` に置き、名前を `<ExternalSystem>Client` にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.externalSystemImplementationsAreClients］
- [ ] Domain のインタフェースの実装を `infrastructure` に置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainInterfacesAreImplementedInInfrastructure］
- [ ] `@Service` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.servicesResideInApplicationOrDomainService］
- [ ] 外部 API の JSON の record の名前を `Request` と `Response` で終えない。［ArchUnit で検査：ClassRoleArchTest.requestsAndResponsesArePresentationWebRecords］
- [ ] `presentation.web`、`infrastructure.persistence` に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `application`、`domain.service`、モジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModel］
- [ ] jOOQ の API と生成型を使わない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.databaseTechnologyApisAreOnlyUsedByPersistenceAdapters］
- [ ] `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods］
- [ ] `@Component` を付けた package-private の class にし、URL を `@Value` で受け取る。［自分で点検］
- [ ] 接続 1 秒、呼び出し 2 秒のタイムアウトを `application.yaml` に書き、コンストラクタで受け取る。［自分で点検］
- [ ] トランザクションの中で呼ぶなら、最悪の時間を `DB_IDLE_IN_TRANSACTION_TIMEOUT_MS` より短くする。［結合テストで検査：DemoApplicationTest.paymentGatewayWorstCaseFitsIdleInTransactionTimeout］
- [ ] 名前付きの instance の `@CircuitBreaker` と `@Retry` をメソッドに付け、`application.yaml` に設定を書く。［自分で点検］
- [ ] circuit breaker の instance に、429 以外の 4xx を無視する `ignore-exception-predicate` を指定する。［結合テストで検査：PaymentGatewayClientIntegrationTest］
- [ ] 業務上の拒否と契約の不備を結果で返し、一時障害と資格情報の不備を例外で投げる。［結合テストで検査：PaymentGatewayClientIntegrationTest］
- [ ] 外部システムの応答を Domain の型に変換して返す。［自分で点検］
- [ ] 契約に合わない応答（201 以外の 2xx、本文がない、状態が未知、識別子がない）を契約の不備の結果にする。［結合テストで検査：PaymentGatewayClientIntegrationTest］
- [ ] クラス、定数、フィールド、コンストラクタ、実装するメソッドに Javadoc を書く。［自分で点検］
- [ ] 冪等性キーを受け取る操作は、キーをヘッダーで送る。［自分で点検］
- [ ] JDK の `HttpServer` で、HTTP の呼び出しと冪等性キーのヘッダーを確かめるテストを書く。［自分で点検］
- [ ] WireMock の結合テストで、リトライ、circuit breaker、イベント出版の状態を確かめる。［自分で点検］
- [ ] `infrastructure.client` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
