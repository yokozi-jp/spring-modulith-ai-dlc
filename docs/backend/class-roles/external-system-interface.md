---
type: Convention
title: クラスの役割：外部システムのインタフェース
description: 決済などの外部システムをドメインの語彙で表す domain.model のインタフェースの定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。外部システムを呼ぶインタフェースを作るとき、メソッドを足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：外部システムのインタフェース

`<ExternalSystem>` は、決済などの外部システムをドメインの語彙で表すインタフェースであり、`domain.model` に置く。
引数と戻り値は値オブジェクトにし、HTTP や製品の型を出さない。
実装は `infrastructure.client` の `<ExternalSystem>Client` が持ち、呼ぶのは `<UseCase>CommandHandler` だけである。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

ユースケースが外部システムを呼ぶとき、通信の方式や製品の API に Application を依存させたくない。
**外部システムのインタフェース**（`<ExternalSystem>`）は、外部システムに頼む業務の操作をドメインの語彙で定める型である。
注文の例では、`PaymentGateway` が注文の代金を請求し、決済の識別子（`PaymentId`）を返す。

インタフェースを Domain に置き、実装を Infrastructure に置くことで、依存は内向きに保たれる。
外部システムの呼び出しは `<UseCase>CommandHandler` が行い、[集約](aggregate.md)と [Domain Service](domain-service.md) は呼ばない。

## 置き場所と命名

- `com.example.demo.<feature>.domain.model` に置く。
- 名前は外部システムの業務上の役割を表す名詞にする（`PaymentGateway`）。
  製品名（`StripeClient`）、`Port`、`Interface` の接尾辞、`I` の接頭辞を付けない。
- メソッドは業務の動詞にする（`charge`）。
- 外部システムが返す識別子は、同じ `domain.model` の[値オブジェクト](value-object.md)にする（`PaymentId`）。
- 実装は `infrastructure.client` の `<ExternalSystem>Client` にする（`PaymentGatewayClient`）。

## 必須の記述

- `public interface` にする。
- メソッドが一つのインタフェースには、`@SuppressWarnings("PMD.ImplicitFunctionalInterface")` を理由のコメントと一緒に付ける。
  PMD が、メソッドが一つのインタフェースを関数型インタフェースとして報告するためである。
- 引数と戻り値は、値オブジェクトと Java の標準型にする。
- インタフェースと各メソッドに Javadoc を書く。
- パッケージの `package-info.java` は集約と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じ `domain.model` の値オブジェクトと enum。
- **依存してはいけない型**：Spring の型（`RestClient`、`ResponseEntity`、`HttpStatusCode`）、Resilience4j の型、Jackson の型、外部システムの SDK の型、`application`、モジュールルートの型、集約。

## 最小の例と典型的な例

最小の例は、注文の代金を請求する `PaymentGateway` と、決済の識別子の `PaymentId` である。

```java
package com.example.demo.order.domain.model;

/** 外部の決済システム。 */
// 外部システムの interface であり、ラムダで実装する関数型 interface ではない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PaymentGateway {

  /** 注文の代金を請求し、決済の識別子を返す。 */
  PaymentId charge(OrderId orderId, Money amount);
}
```

```java
package com.example.demo.order.domain.model;

/** 外部の決済システムが採番した決済の識別子。 */
public record PaymentId(String value) {

  /** 空白だけの ID を拒否する。 */
  public PaymentId {
    if (value.isBlank()) {
      throw new IllegalArgumentException("paymentId must not be blank");
    }
  }
}
```

典型的な例は、`ConfirmOrderCommandHandler` が注文を確定する前に代金を請求する場面と、実装の宣言である。

```java
// com.example.demo.order.application.ConfirmOrderCommandHandler（抜粋）
/** ロック番号を確かめ、代金を請求して注文を確定する。 */
@Transactional
public ConfirmOrderResult handle(final ConfirmOrderCommand command) {
  final Order order =
      orderRepository
          .findById(new OrderId(command.orderId()))
          .orElseThrow(
              () -> new NoSuchElementException("order not found: orderId=" + command.orderId()));
  order.ensureLockNo(command.lockNo());
  paymentGateway.charge(order.id(), order.total());
  order.confirm();
  orderRepository.update(order);
  return new ConfirmOrderResult(order.id().value());
}
```

```java
// com.example.demo.order.infrastructure.client.PaymentGatewayClient（宣言だけ）
@Component
class PaymentGatewayClient implements PaymentGateway {
  // RestClient で外部の決済システムを呼び、応答を PaymentId に変換する。
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
- [ ] 呼び出しは `<UseCase>CommandHandler` から行い、集約と Domain Service から呼ばない。［自分で点検］
- [ ] `@SuppressWarnings("PMD.ImplicitFunctionalInterface")` に理由のコメントを付け、インタフェースと各メソッドに Javadoc を書く。［自分で点検］
- [ ] `domain.model` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
