---
type: Convention
title: クラスの役割：集約
description: domain.model に置く集約（集約ルートのクラス）の定義、置き場所と命名、必須の記述（ロック番号と更新の競合の例外を含む）、依存、状態遷移の例、テスト、アンチパターン、作成時のチェックリストを定める。集約を作るとき、状態を変える操作を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：集約

集約は業務上の一貫性を保つ単位であり、集約ルートのクラスが状態と不変条件を持つ。
`domain.model` に `public final class` として置き、状態は業務の操作を表すメソッドだけで変える。
許されない状態遷移では `IllegalStateException` を、不正な引数では `IllegalArgumentException` を、画面から受け取ったロック番号の不一致では `<Aggregate>ConflictException` を投げる。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

**集約**は、まとめて一貫性を保つ [Entity](entity.md) と[値オブジェクト](value-object.md)の集まりである。
外から参照と変更ができるのは**集約ルート**だけであり、集約ルートのクラス名を集約の名前にする（`Order`）。
集約の中の Entity（`OrderLine`）は、集約ルートのメソッドを通してだけ変わる。

集約は、永続化の Record、API の Request と Response、モジュールルートの record を兼ねない。
保存と取り出しは [Repository](repository.md) が担う。

注文の集約は、次の状態遷移を持つ。

```text
受付（PLACED） ──confirm()──> 確定（CONFIRMED） ──markPaid()──> 支払い済み（PAID） ──ship()──> 出荷（SHIPPED）
     │
     └──cancel()──> 取消（CANCELLED）
```

取消は受付からだけでき、受付でない注文の `cancel()` は `IllegalStateException` を投げる。
確定の後は決済が非同期で進み、確定の後の取消には返金が要るため、この例では扱わない（[ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md)）。
`markPaid()` は確定のときだけでき、`isPaid()` は支払い済みと出荷のときに `true` を返す。
決済は、確定のイベントを受けた `ChargeOrderCommandHandler` が行う（[CommandHandler](command-handler.md)）。
割引の適用（`applyDiscount`）と明細の数量の変更（`changeLineQuantity`）は、受付のときだけできる。

集約ルートは、[PostgreSQL の共通カラム](../../database/postgresql-common-columns.md)の `lock_no` を `lockNo` として持つ。
`lockNo` は、[PostgreSQL の排他制御](../../database/postgresql-concurrency-control.md)の楽観的ロックで、画面が読んだ集約と保存済みの集約が同じ版であることを確かめるための値である。
業務の判定には使わない。

## 置き場所と命名

- `com.example.demo.<feature>.domain.model` に置く。
- 名前は集約ルートを表すユビキタス言語の名詞にする（`Order`）。
  `OrderEntity`、`OrderModel`、`OrderAggregate` にしない。
- 新規作成は業務の動詞の static メソッドにし（`Order.place`）、保存済みの状態は `Order.restore` で作る。
- 更新の競合を表す例外は、集約と同じ `domain.model` に、集約の名前に `ConflictException` を付けて置く（`OrderConflictException`）。
  CommandHandler と Repository の実装の両方が使うため、`domain.model` に置く。
- 状態を変えるメソッドは業務の動詞にし（`confirm`、`markPaid`、`ship`、`cancel`、`applyDiscount`、`changeLineQuantity`）、アクセサは `get` を付けず record と同じ形（`id()`）にする。
  状態を問うメソッドは `is` で始める（`isPaid`）。

## 必須の記述

- `public final class` にし、アノテーションを付けない。
  コンストラクタは private にし、`place` と `restore` から呼ぶ。
- 生成時に決まる値は `private final` フィールドにし、変わる状態（`status`、`discount`）だけを final でないフィールドにする。
- ロック番号は `private final long lockNo` に持ち、`restore` の最後の引数で受け取る。
  `place` は、INSERT で登録する値と同じ `1` にする。
- 画面から受け取ったロック番号を比べる public メソッド `ensureLockNo(long lockNo)` を置き、違えば `<Aggregate>ConflictException` を投げる。
  CommandHandler が DB から読み直した集約の `lockNo` を `ensureLockNo` が画面の値と比べ、Repository の `update` がその `lockNo` を UPDATE の条件で更新の時点の行の値と比べる（[jOOQ の Repository](jooq-repository.md)、[ADR-052](../../adr/ADR-052-detect-optimistic-lock-conflicts-by-update-count.md)）。
- `<Aggregate>ConflictException` は `RuntimeException` を継承した `public final class` にし、メッセージを受け取るコンストラクタと、メッセージと原因を受け取るコンストラクタを持つ。
- setter を作らず、子の Entity のリストは `List.copyOf` で保持する。
- 他の集約は識別子（`CustomerId`）で持ち、現在時刻は引数の `Instant` で受け取る。
- 例外のメッセージは英語にし、対象の識別子を `orderId=...` の形で含める。
- アクセサがフィールド名と同じ名前になるため、クラスに `@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})` を理由のコメントと一緒に付ける。
- クラス、フィールド、public メソッドに Javadoc を書く。
- `domain.model` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

Domain が投げる JDK の例外と `<Aggregate>ConflictException` は、いまは HTTP の 500 になる。
ユースケースがこの例外を 400、404、409、422 で返す必要があるときは、実装を止めて利用者に確認し、対応づけを新しい ADR で決める。
ステータスコードの使い分けは[HTTPステータスコードの選択](../../web-api/status-codes.md)に、API のエラー契約は [ADR-013](../../adr/ADR-013-standardize-http-api-contracts.md) に従う。
対応づけの作業は [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107) で扱う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じ `domain.model` の Entity、値オブジェクト、enum、`<Aggregate>ConflictException`。
- **依存してはいけない型**：Spring、jOOQ、jOOQ の生成型、JPA、Jackson の型、`domain.service`、`application`、モジュールルートの型、他モジュールの型、Repository と外部システムのインタフェース、`Clock`。

## 最小の例と典型的な例

最小の例は、注文を受け付けて確定するまでの `Order` である。

```java
package com.example.demo.order.domain.model;

/** 注文の集約ルート。 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class Order {

  /** 注文 ID。 */
  private final OrderId id;

  /** 注文した顧客。 */
  private final CustomerId customerId;

  /** 注文の明細。 */
  private final List<OrderLine> lines;

  /** 受け付けた時刻。 */
  private final Instant placedAt;

  /** 楽観的ロックのロック番号。 */
  private final long lockNo;

  /** 注文の状態。 */
  private OrderStatus status;

  /** 割引額。 */
  private Money discount;

  /** 明細が空でないことを確かめて状態を持つ。 */
  private Order(
      final OrderId id,
      final CustomerId customerId,
      final OrderStatus status,
      final List<OrderLine> lines,
      final Money discount,
      final Instant placedAt,
      final long lockNo) {
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("order lines must not be empty: orderId=" + id.value());
    }
    this.id = id;
    this.customerId = customerId;
    this.status = status;
    this.lines = List.copyOf(lines);
    this.discount = discount;
    this.placedAt = placedAt;
    this.lockNo = lockNo;
  }

  /** 注文を受け付ける。 */
  public static Order place(
      final OrderId id,
      final CustomerId customerId,
      final List<OrderLine> lines,
      final Instant placedAt) {
    return new Order(id, customerId, OrderStatus.PLACED, lines, Money.ZERO, placedAt, 1L);
  }

  /** 受付の注文を確定する。 */
  public void confirm() {
    ensureStatus(OrderStatus.PLACED);
    status = OrderStatus.CONFIRMED;
  }

  /** 注文 ID を返す。 */
  public OrderId id() {
    return id;
  }

  // customerId()、status()、discount()、placedAt()、lockNo() も同じ形で返し、lines() は List.copyOf(lines) を返す。

  private void ensureStatus(final OrderStatus expected) {
    if (status != expected) {
      throw new IllegalStateException(
          "order is not " + expected + ": orderId=" + id.value() + ", status=" + status);
    }
  }
}
```

典型的な例は、残りの操作を足した `Order` である（抜粋）。
明細の数量を変える `changeLineQuantity` は [Entity](entity.md) の例に示す。

```java
/** 保存済みの注文を復元する。Repository の実装が使う。 */
public static Order restore(
    final OrderId id,
    final CustomerId customerId,
    final OrderStatus status,
    final List<OrderLine> lines,
    final Money discount,
    final Instant placedAt,
    final long lockNo) {
  return new Order(id, customerId, status, lines, discount, placedAt, lockNo);
}

/** 画面が読んだ注文のロック番号が、保存済みの注文のロック番号と一致することを確かめる。 */
public void ensureLockNo(final long lockNo) {
  if (this.lockNo != lockNo) {
    throw new OrderConflictException(
        "order was updated by another request: orderId=" + id.value() + ", lockNo=" + lockNo);
  }
}

/** 確定の注文を支払い済みにする。 */
public void markPaid() {
  ensureStatus(OrderStatus.CONFIRMED);
  status = OrderStatus.PAID;
}

/** 代金を支払い済みかを返す。支払い済みと出荷の注文で true を返す。 */
public boolean isPaid() {
  return status == OrderStatus.PAID || status == OrderStatus.SHIPPED;
}

/** 支払い済みの注文を出荷する。 */
public void ship() {
  ensureStatus(OrderStatus.PAID);
  status = OrderStatus.SHIPPED;
}

/** 受付の注文を取り消す。 */
public void cancel() {
  ensureStatus(OrderStatus.PLACED);
  status = OrderStatus.CANCELLED;
}

/** 受付の注文に、小計を超えない割引を適用する。 */
public void applyDiscount(final Money discount) {
  ensureStatus(OrderStatus.PLACED);
  if (discount.amount().compareTo(subtotal().amount()) > 0) {
    throw new IllegalArgumentException("discount exceeds subtotal: orderId=" + id.value());
  }
  this.discount = discount;
}

/** 明細の小計の合計を返す。 */
public Money subtotal() {
  return lines.stream().map(OrderLine::subtotal).reduce(Money.ZERO, Money::plus);
}

/** 小計から割引額を引いた合計を返す。 */
public Money total() {
  return subtotal().minus(discount);
}
```

更新の競合の例外は、次の `OrderConflictException` である。

```java
package com.example.demo.order.domain.model;

/** 注文の更新が、ほかの更新と競合したことを表す。 */
public final class OrderConflictException extends RuntimeException {

  /** 直列化の版。 */
  private static final long serialVersionUID = 1L;

  /** 競合の内容を受け取る。 */
  public OrderConflictException(final String message) {
    super(message);
  }

  /** 競合の内容と、行をロックできなかった原因を受け取る。 */
  public OrderConflictException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
```

## 対応するテスト

Spring を起動しない JUnit のテストを、集約と同じパッケージのテストソースに置く（`backend/src/test/java/com/example/demo/order/domain/model/OrderTest.java`）。
許される状態遷移と許されない状態遷移を、操作ごとに確かめる。
`ensureLockNo` は、違うロック番号で `OrderConflictException` を投げることを確かめる。

```java
/** 注文の状態遷移を検証する。 */
class OrderTest {

  @Test
  @DisplayName("出荷した注文は取り消せない")
  void shippedOrderCannotBeCancelled() {
    final Order order =
        Order.place(
            new OrderId("O-1"),
            new CustomerId("C-1"),
            List.of(
                new OrderLine(
                    1, new ProductCode("P-1"), new Quantity(2), new Money(new BigDecimal("500")))),
            Instant.parse("2026-10-03T00:00:00Z"));
    order.confirm();
    order.markPaid();
    order.ship();

    assertThatThrownBy(order::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("orderId=O-1");
  }
}
```

## アンチパターン

- setter（`setStatus`）で状態を書き換える。
  不変条件の検査を通らずに状態が変わる。
- 状態遷移の判定を CommandHandler に書き、集約をデータの入れ物にする。
  [ドメインモデル貧血症](https://www.martinfowler.com/bliki/AnemicDomainModel.html)になり、同じ判定がユースケースごとに重複する。
- 集約の中で Repository、`Clock`、引数なしの `Instant.now()` を使う。
- 他の集約（`Customer`）をフィールドで持ち、一つの操作で二つの集約を変える。
- `lockNo` を業務の判定に使う、または状態を変える操作で `lockNo` を書き換える。
  `lock_no` を進めるのは Repository の実装の `update` だけである。
- `updated_at` などの共通カラムを集約のフィールドに持つ。
  共通カラムは業務ロジックで参照しない（[PostgreSQL の共通カラム](../../database/postgresql-common-columns.md)）。

## 作成時のチェックリスト

- [ ] `domain.model` に置き、Spring、jOOQ、JPA、Jackson に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks］
- [ ] `domain.service`、`application`、モジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `public final class` にし、private のコンストラクタを `place` などの業務の動詞の static メソッドと `restore` から呼ぶ。［自分で点検］
- [ ] setter を作らず、状態は業務の操作のメソッドだけで変える。［自分で点検］
- [ ] 許されない状態遷移で `IllegalStateException` を、不正な引数で `IllegalArgumentException` を投げ、メッセージに識別子を含める。［自分で点検］
- [ ] `private final long lockNo` を `restore` の最後の引数で受け取り、`place` で `1` にし、`ensureLockNo` で違えば `<Aggregate>ConflictException` を投げる。［自分で点検］
- [ ] `<Aggregate>ConflictException` を `domain.model` に `RuntimeException` を継承した `public final class` で置く。［自分で点検］
- [ ] `lockNo` 以外の共通カラムを集約に持たない。［自分で点検］
- [ ] 他の集約は識別子で持ち、現在時刻は引数で受け取る。［自分で点検］
- [ ] 子の Entity のリストは `List.copyOf` で持ち、変更できないリストで返す。［自分で点検］
- [ ] アクセサの `@SuppressWarnings` に理由のコメントを付け、クラス、フィールド、public メソッドに Javadoc を書く。［自分で点検］
- [ ] Domain の例外を 400、404、409、422 で返す必要があるなら、実装を止めて利用者に確認した。［自分で点検］
- [ ] 状態遷移ごとに、Spring を起動しない JUnit のテストを書く。［自分で点検］
- [ ] `domain.model` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
