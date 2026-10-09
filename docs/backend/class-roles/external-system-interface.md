---
type: Convention
title: クラスの役割：外部システムのインタフェース
description: 決済などの外部システムをドメインの語彙で表す domain.model のインタフェースの定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。外部システムを呼ぶインタフェースを作るとき、メソッドを足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：外部システムのインタフェース

`<ExternalSystem>` は、決済などの外部システムをドメインの語彙で表すインタフェースであり、`domain.model` に置く。
引数と戻り値は値オブジェクトにし、HTTP や製品の型を出さない。
実装は `infrastructure.client` の `<ExternalSystem>Client` が持ち、呼ぶのは、イベントを受けた Listener から呼ばれる `<UseCase>CommandHandler`（`ChargeOrderCommandHandler`）だけである。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

ユースケースが外部システムを呼ぶとき、通信の方式や製品の API に Application を依存させたくない。
**外部システムのインタフェース**（`<ExternalSystem>`）は、外部システムに頼む業務の操作をドメインの語彙で定める型である。
決済の例では、`PaymentGateway` が注文の代金を請求し、請求の結果（`ChargeOutcome`）を返す。

インタフェースを Domain に置き、実装を Infrastructure に置くことで、依存は内向きに保たれる。
外部システムの呼び出しは、イベントを受けた Listener から呼ばれる `<UseCase>CommandHandler` が行い、画面から呼ばれる CommandHandler、[集約](aggregate.md)、[Domain Service](domain-service.md) は呼ばない。
画面から呼ばれる CommandHandler のトランザクションの中で呼ばない理由は、[CommandHandler](command-handler.md) と [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 置き場所と命名

- `com.example.demo.<feature>.domain.model` に置く。
- 名前は外部システムの業務上の役割を表す名詞にする（`PaymentGateway`）。
  製品名（`StripeClient`）、`Port`、`Interface` の接尾辞、`I` の接頭辞を付けない。
- メソッドは業務の動詞にする（`charge`）。
- 外部システムが返す識別子は、同じ `domain.model` の[値オブジェクト](value-object.md)にする（`GatewayPaymentCode`）。
- 業務上の拒否のように、例外でなく業務の状態に記録する応答があるときは、結果の値オブジェクトを返す（`ChargeOutcome`、[ADR-072](../../adr/ADR-072-fake-external-systems-with-wiremock.md)）。
- 実装は `infrastructure.client` の `<ExternalSystem>Client` にする（`PaymentGatewayClient`）。

## 必須の記述

- `public interface` にする。
- メソッドが一つのインタフェースには、`@SuppressWarnings("PMD.ImplicitFunctionalInterface")` を理由のコメントと一緒に付ける。
  PMD が、メソッドが一つのインタフェースを関数型インタフェースとして報告するためである。
- 引数と戻り値は、値オブジェクトと Java の標準型にする。
- 副作用のある操作は、重複を防ぐ冪等性キーを引数に持ち、Javadoc にそう書く（`charge` は `OrderId` を冪等性キーにする）。
  冪等性キーの扱いは[順序保証と冪等性](../../integration/async-ordering-and-idempotency.md)に従う。
- インタフェースと各メソッドに Javadoc を書く。
- パッケージの `package-info.java` は集約と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じ `domain.model` の値オブジェクトと enum。
- **依存してはいけない型**：Spring の型（`RestClient`、`ResponseEntity`、`HttpStatusCode`）、Resilience4j の型、Jackson の型、外部システムの SDK の型、`application`、モジュールルートの型、集約。

## 最小の例と典型的な例

最小の例は、注文の代金を請求する `PaymentGateway` と、決済代行が採番した識別子の `GatewayPaymentCode` である。
請求の結果の `ChargeOutcome` は、`GatewayPaymentCode` と `PaymentStatus` を持つ値オブジェクトである。

```java
package com.example.demo.payment.domain.model;

/** 外部の決済代行。 */
// 外部システムの interface であり、ラムダで実装する関数型 interface ではない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PaymentGateway {

  /**
   * 注文の代金を請求し、業務の状態に記録する結果を返す。
   *
   * <p>注文 ID を冪等性キーにする。
   * 同じ注文 ID の二回目以降の請求では、決済代行は新たに請求せず、最初の請求の結果を返す。
   */
  ChargeOutcome charge(OrderId orderId, Money amount);
}
```

```java
package com.example.demo.payment.domain.model;

/** 決済代行が採番した決済の識別子。 */
public record GatewayPaymentCode(String value) {

  /** 空白だけの識別子を拒否する。 */
  public GatewayPaymentCode {
    if (value.isBlank()) {
      throw new IllegalArgumentException("gatewayPaymentCode must not be blank");
    }
  }
}
```

典型的な例は、`ChargeOrderCommandHandler` が、確定した注文の代金を注文 ID を冪等性キーにして請求し、結果を決済記録に保存する場面と、実装の宣言である。

```java
// com.example.demo.payment.application.ChargeOrderCommandHandler（抜粋）
final Money amount = new Money(details.totalAmount());
final ChargeOutcome outcome = paymentGateway.charge(orderId, amount);
final Payment payment = Payment.record(orderId, amount, outcome, Instant.now(clock));
paymentRepository.add(payment);
```

```java
// com.example.demo.payment.infrastructure.client.PaymentGatewayClient（宣言だけ）
@Component
class PaymentGatewayClient implements PaymentGateway {
  // RestClient で決済代行を呼び、注文 ID を冪等性キーのヘッダーで送り、応答を ChargeOutcome に変換する。
}
```

## 対応するテスト

インタフェース自体の専用のテストは作らない。
実装の `PaymentGatewayClient` は、Spring を起動しない JUnit のテストで、JDK の `com.sun.net.httpserver.HttpServer` を空いているポートで起動して確かめる。

## アンチパターン

- 外部システムの API の形をそのまま写し、HTTP のリクエストや応答の型を引数と戻り値に使う。
  外部システムを替えると Domain と Application も変わる。
- 製品名でインタフェースを名付ける（`StripeClient`）。
- 外部システムを集約や Domain Service から呼ぶ。
- 画面から呼ばれる CommandHandler から外部システムを呼ぶ。
  詳細は [CommandHandler](command-handler.md) のアンチパターンに示す。
- 副作用のある操作に冪等性キーを持たせない。
  ロールバックやイベント出版の再投入でもう一度呼ぶと、同じ請求が二回実行される。
- 実装を `infrastructure.client` 以外に置く、または `<ExternalSystem>Adapter` と名付ける。
- 接続先の URL やタイムアウトをインタフェースの引数で渡す。
  通信の設定は実装の `<ExternalSystem>Client` が持つ。

## 作成時のチェックリスト

- [ ] `domain.model` に置き、Spring、jOOQ、JPA、Jackson に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks］
- [ ] `application` とモジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] 名前は外部システムの業務上の役割を表す名詞にし、メソッドは業務の動詞にする。［自分で点検］
- [ ] 引数と戻り値は値オブジェクトと Java の標準型にする。［自分で点検］
- [ ] 実装は `infrastructure.client` の `<ExternalSystem>Client` にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.externalSystemImplementationsAreClients］
- [ ] Domain の外の実装は `infrastructure` に置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainInterfacesAreImplementedInInfrastructure］
- [ ] 呼び出しは、イベントを受けた Listener から呼ばれる `<UseCase>CommandHandler` から行い、画面から呼ばれる CommandHandler、集約、Domain Service から呼ばない。［自分で点検］
- [ ] 副作用のある操作は冪等性キーを引数に持ち、Javadoc に書く。［自分で点検］
- [ ] `@SuppressWarnings("PMD.ImplicitFunctionalInterface")` に理由のコメントを付け、インタフェースと各メソッドに Javadoc を書く。［自分で点検］
- [ ] `domain.model` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
