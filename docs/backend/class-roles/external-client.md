---
type: Convention
title: クラスの役割：外部システムの Client
description: infrastructure.client に置き、外部システムのインタフェースを HTTP で実装する Client の定義、置き場所と命名、必須の記述（タイムアウトと名前付きの Resilience4j の instance）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。外部システムの HTTP API を呼ぶ実装を作るときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：外部システムの Client

`<ExternalSystem>Client` は、`domain.model` の外部システムのインタフェースを HTTP で実装するクラスであり、`infrastructure.client` に package-private で置いて `@Component` を付ける。
接続先の URL は設定から受け取り、タイムアウトを設定した `RestClient` で呼ぶ。
名前付きの Resilience4j の instance を、この層のメソッドにだけ付ける。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

Listener から呼ばれる CommandHandler は、決済のような外部システムを[外部システムのインタフェース](external-system-interface.md)で呼び、HTTP の詳細を知らない。
**外部システムの Client**（`<ExternalSystem>Client`）は、そのインタフェースを、外部システムの HTTP API の呼び出しで実装するクラスである。
URL、JSON の形、タイムアウト、サーキットブレーカー、リトライは、Client だけが扱う。

Client は業務規則を持たない。
外部システムの応答を Domain の型に変換して返すだけにする。

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
- コンストラクタは一つにし、`@Value("${payment-gateway.base-url}") final String baseUrl` を受け取る。
- コンストラクタで、`JdkClientHttpRequestFactory` に接続のタイムアウト 1 秒と呼び出しのタイムアウト 2 秒を設定し、`RestClient` を作る。
- 実装するメソッドに `@CircuitBreaker(name = "payment-gateway")` と `@Retry(name = "payment-gateway")` を付ける。
  リトライはこの層だけで行い、CommandHandler や HTTP クライアントで重ねない。
- instance の設定は、`application.yaml` の `resilience4j` に、既定の設定を継承して書く。
  冪等性キーを渡さない更新の操作は `retry` の `default` を、GET などの冪等な操作は `idempotent` を継承する。
  `charge` は冪等性キーで重複を防げるが、`ChargeOrderCommandHandler` のトランザクションの中で呼ぶため、`default` を継承して試行を一回にし、失敗した請求はイベント出版の再投入でやり直す。
- 冪等性キーを受け取る操作は、キーを外部システムの API が定めるヘッダー（決済システムの例では `Idempotency-Key`）で送る。
- 外部システムの応答は、Domain の型（`PaymentId`）に変換して返す。
  応答の本文がないときは `IllegalStateException` を投げる。
- クラス、定数、フィールド、コンストラクタ、実装するメソッドに Javadoc を書く。
- `infrastructure.client` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

タイムアウト、リトライ、サーキットブレーカーの値は [ADR-019](../../adr/ADR-019-define-resilience-and-capacity-guardrails.md) に従う。

```yaml
payment-gateway:
  base-url: ${PAYMENT_GATEWAY_BASE_URL}

resilience4j:
  circuitbreaker:
    instances:
      payment-gateway:
        base-config: default
  retry:
    instances:
      payment-gateway:
        base-config: default
```

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じモジュールの `domain.model` の外部システムのインタフェースと値オブジェクト、Spring の `RestClient`、`JdkClientHttpRequestFactory`、`@Component`、`@Value`、Resilience4j の `@CircuitBreaker` と `@Retry`。
- **依存してはいけない型**：`application`、`domain.service`、`presentation.web`、`infrastructure.persistence` の型、jOOQ の API と生成型、モジュールルートの型、他モジュールの型、`@Service`、`@Transactional`。

## 最小の例と典型的な例

最小の例は、決済システムに代金を請求する `PaymentGatewayClient` である。

```java
package com.example.demo.ordering.infrastructure.client;

/** 決済システムの HTTP API で、注文 ID を冪等性キーにして注文の代金を請求する。 */
@Component
class PaymentGatewayClient implements PaymentGateway {

  /** 接続の確立を待つ上限。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

  /** 一回の呼び出しの応答を待つ上限。 */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

  /** 決済システムが冪等性キーを受け取るヘッダー。 */
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** 決済システムを呼ぶ HTTP クライアント。 */
  private final RestClient restClient;

  /** 決済システムの URL を受け取り、タイムアウトを設定した HTTP クライアントを作る。 */
  /* package */ PaymentGatewayClient(@Value("${payment-gateway.base-url}") final String baseUrl) {
    final JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    requestFactory.setReadTimeout(READ_TIMEOUT);
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  /** 注文の代金を請求し、決済 ID を返す。 */
  @CircuitBreaker(name = "payment-gateway")
  @Retry(name = "payment-gateway")
  @Override
  public PaymentId charge(final OrderId orderId, final Money amount) {
    final ChargeReply reply =
        restClient
            .post()
            .uri("/payments")
            .header(IDEMPOTENCY_KEY, orderId.value())
            .body(new ChargeBody(orderId.value(), amount.amount()))
            .retrieve()
            .body(ChargeReply.class);
    if (reply == null) {
      throw new IllegalStateException("payment gateway returned no body: orderId=" + orderId.value());
    }
    return new PaymentId(reply.paymentId());
  }

  /** 請求の API に送る本文。 */
  private record ChargeBody(String orderId, BigDecimal amount) {}

  /** 請求の API が返す本文。 */
  private record ChargeReply(String paymentId) {}
}
```

典型的な例は、`ChargeOrderCommandHandler` がインタフェースを通して Client を呼ぶ場面である。
CommandHandler は `PaymentGateway` に依存し、`PaymentGatewayClient` を知らない。

```java
// com.example.demo.ordering.application.ChargeOrderCommandHandler（抜粋）
if (order.isPaid()) {
  return new ChargeOrderResult(order.id().value().toString());
}
paymentGateway.charge(order.id(), order.total());
order.markPaid();
orderRepository.update(order);
```

## 対応するテスト

Spring を起動しない JUnit のテストで、JDK の `com.sun.net.httpserver.HttpServer` を空いているポートで起動し、Client に URL を渡す。
HTTP の本文と Domain の型の変換を確かめ、応答を遅らせたときにタイムアウトすることも同じ方法で確かめる。
冪等性キーのヘッダーも同じ方法で確かめる。
Spring を起動しないため、このテストでは `@CircuitBreaker` と `@Retry` は働かない。

```java
/** 決済システムの Client の HTTP の呼び出しを検証する。 */
class PaymentGatewayClientTest {

  @Test
  @DisplayName("注文 ID を冪等性キーにして請求し、決済システムが返した決済 ID を返す")
  void chargesWithOrderIdAsIdempotencyKey() throws IOException {
    final UUID orderUuid = UUID.fromString("00000000-0000-4000-8000-000000000001");
    final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/payments",
        exchange -> {
          idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          final byte[] body = "{\"paymentId\":\"PAY-1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(201, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      final PaymentGatewayClient client =
          new PaymentGatewayClient("http://localhost:" + server.getAddress().getPort());

      final PaymentId paymentId =
          client.charge(new OrderId(orderUuid), new Money(new BigDecimal("1000")));

      assertThat(paymentId).as("orderId の決済 ID").isEqualTo(new PaymentId("PAY-1"));
      assertThat(idempotencyKey).as("orderId の請求の冪等性キー").hasValue(orderUuid.toString());
    } finally {
      server.stop(0);
    }
  }
}
```

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
- URL をコードに直接書く。
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
- [ ] 接続 1 秒、呼び出し 2 秒のタイムアウトを設定する。［自分で点検］
- [ ] 名前付きの instance の `@CircuitBreaker` と `@Retry` をメソッドに付け、`application.yaml` に設定を書く。［自分で点検］
- [ ] 外部システムの応答を Domain の型に変換して返す。［自分で点検］
- [ ] クラス、定数、フィールド、コンストラクタ、実装するメソッドに Javadoc を書く。［自分で点検］
- [ ] 冪等性キーを受け取る操作は、キーをヘッダーで送る。［自分で点検］
- [ ] JDK の `HttpServer` で、HTTP の呼び出しと冪等性キーのヘッダーを確かめるテストを書く。［自分で点検］
- [ ] `infrastructure.client` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
