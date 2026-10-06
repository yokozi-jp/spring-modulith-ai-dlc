---
type: Convention
title: クラスの役割：業務上の失敗の例外
description: 業務上の失敗を表す例外（NotFoundException、BusinessRuleViolationException、ConflictException）の定義、置き場所、HTTP のステータス、ログレベル、Listener での扱い、例、テスト、アンチパターン、作成時のチェックリストを定める。見つからない、業務規則の違反、競合を例外で表すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：業務上の失敗の例外

業務上の失敗は、`shared.failure` の `NotFoundException`（404）と `BusinessRuleViolationException`（422）、`shared.concurrency` の `ConflictException`（409）の三つの型だけで投げる。
集約ごとの型を作らず、JDK の例外と Spring の HTTP の例外を業務上の失敗に使わない。
HTTP のステータスとログレベルは `error` モジュールの `ApiExceptionHandler` が決め、Controller は try と catch を書かない。
決定理由は [ADR-062](../../adr/ADR-062-map-business-exceptions-to-404-409-422.md) に示す。

## 定義

利用者の要求が業務の上で成り立たないとき、Domain、Application、Infrastructure は、その失敗を例外で呼び出し側へ伝える。
**業務上の失敗の例外**は、利用者が要求か状態を見直せば解消しうる失敗を表す、次の三つの型である。

- **NotFoundException**：指定された集約や行が存在しない。
  参照の権限がなく存在を隠す場合も同じ型を投げる。
- **BusinessRuleViolationException**：許されない状態遷移、業務規則の違反、要求を今の状態へ適用できない。
- **ConflictException**：版の不一致、行ロックの失敗、一意制約の違反。
  読み直せば解消しうる。

プログラムの誤り（主キーの条件が 2 行に合う、束縛すべき値がない）は業務上の失敗ではない。
`IllegalStateException` と `IllegalArgumentException` で投げ、HTTP の 500 にする。

## 置き場所と命名

- `NotFoundException` と `BusinessRuleViolationException` は `com.example.demo.shared.failure` に、`ConflictException` は `com.example.demo.shared.concurrency` に置く。
- 三つの型は Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。
- 新しい型を足さない。
  四つ目の型が要るときは ADR で決める。
- 集約ごとの型（`OrderNotFoundException`）を作らない。
  三つの型は `final` であり、継承できない。

## 必須の記述

- メッセージは開発者向けの英語にし、対象と識別子を `order not found: orderId=...` の形で含める。
  メッセージは応答の本文に出ず、ログにだけ出る。
- `Optional` からは `orElseThrow(() -> new NotFoundException("order not found: orderId=" + orderId))` で取り出す。
- 集約の状態遷移と Domain Service の業務規則の違反は `BusinessRuleViolationException` で投げる。
- Repository の実装は、Spring と jOOQ の例外（`CannotAcquireLockException`、`DuplicateKeyException`）を Repository の外へ出さない。
  外へ出すときは、原因に付けた `ConflictException` に変えて投げる（[jOOQ の Repository](jooq-repository.md)）。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型。
- **依存してはいけない型**：Spring Web、Spring の HTTP、Servlet の型（`ResponseStatusException`、`HttpStatus`）。
  `shared` の型が HTTP に依存すると、Domain が HTTP の境界に結びつく。

## 例外、HTTP、ログ、Listener の対応

| 例外                                                                                            | HTTP | 本文                                                         | API のログ                                     | 非同期の Listener            |
| ----------------------------------------------------------------------------------------------- | ---- | ------------------------------------------------------------ | ---------------------------------------------- | ---------------------------- |
| `NotFoundException`                                                                             | 404  | `about:blank`、`title` は `problem.title.404`、`detail` なし | INFO、`cause` 付き、`API business failure`     | 回復不能として再試行しない   |
| `BusinessRuleViolationException`                                                                | 422  | `about:blank`、`title` は `problem.title.422`、`detail` なし | INFO、`cause` 付き、`API business failure`     | 回復不能として再試行しない   |
| `ConflictException`                                                                             | 409  | `about:blank`、`title` は `problem.title.409`、`detail` なし | INFO、`cause` 付き、`API conflict`             | 回復不能として再試行しない   |
| JDK の例外ほか（`IllegalStateException`、`IllegalArgumentException`、`NoSuchElementException`） | 500  | `about:blank`、`title` は `problem.title.500`、`detail` なし | ERROR、`cause` 付き、`Unhandled API exception` | 予期しない例外として送出する |

`title` は `Accept-Language` から解決した言語で返り、404 の本文は対象がない場合と存在を隠す場合で同じになる。
Listener の扱いは[非同期処理の失敗時の再試行と回復](../../integration/async-failure-recovery.md)の「Spring Modulith のイベントの失敗」に合わせる。
業務上の失敗の三つの型は、自動で再試行しない。
ステータス管理テーブル（[非同期処理のステータス管理](../../integration/async-job-status.md)）を持つ処理は FAILED と失敗の内容を記録して正常終了し、持たない処理は `on` から送出してイベント出版を未完了のまま残す（レジストリが DLQ を兼ねる）。
ステータス管理テーブルを持たない処理では、`ConflictException` で未完了のまま残ったイベント出版を運用者が `IncompleteEventPublications` で再投入すると、読み直して成功する見込みがある。

`23505` と `55P03` の原因の例外のメッセージには SQL が入り、`23505` では重複したキーの値も入って、INFO のログに出る。
扱いは[可観測性の規約](../../observability/conventions.md)の「発生源で渡さない値」に従い、閲覧の制限と表示時のマスクで守る。

## 最小の例と典型的な例

最小の例は、CommandHandler で見つからない集約を `NotFoundException` で投げる `handle` である（抜粋）。

```java
final Order order =
    orderRepository
        .findById(new OrderId(UUID.fromString(command.orderId())))
        .orElseThrow(
            () -> new NotFoundException("order not found: orderId=" + command.orderId()));
```

典型的な例は、集約の許されない状態遷移を `BusinessRuleViolationException` で投げる `Order` である（抜粋）。

```java
private void ensureStatus(final OrderStatus expected) {
  if (status != expected) {
    throw new BusinessRuleViolationException(
        "order is not " + expected + ": orderId=" + id.value() + ", status=" + status);
  }
}
```

Controller は参照の結果がなければ `NotFoundException` を投げ、ステータスを自分で決めない（[Controller](controller.md)）。

```java
@GetMapping("/{orderId}")
/* package */ OrderDetailsResponse details(@PathVariable final String orderId) {
  return orderQueries
      .findDetails(orderId)
      .map(OrderDetailsResponse::from)
      .orElseThrow(() -> new NotFoundException("order not found: orderId=" + orderId));
}
```

## 対応するテスト

集約、値オブジェクト、Domain Service の単体テストで、投げる例外の型と、メッセージの識別子を確かめる（`isInstanceOf(BusinessRuleViolationException.class)`）。
例外から HTTP のステータス、本文、ログへの対応づけは `ApiErrorContractTest` と `ApiExceptionHandlerTest` が確かめるため、機能ごとのテストでは確かめない。

## アンチパターン

- 集約ごとの例外の型（`OrderNotFoundException`）を作る。
  `ApiExceptionHandler` が知る型が集約の数だけ増える。
- 見つからないことや業務規則の違反を `NoSuchElementException`、`IllegalStateException` で投げる。
  HTTP の 500 になり、プログラムの誤りと区別できない。
- `ResponseStatusException` を投げる、または `@ResponseStatus` を付ける。
  Domain と `shared` が HTTP の型に依存し、`@ResponseStatus` は `@ExceptionHandler(Exception.class)` が先に受けて効かない。
- Controller に try と catch を書き、例外をステータスへ変える。
  同じ対応づけが Controller ごとに重複し、ログの記録が `ApiExceptionHandler` と食い違う。
- 例外のメッセージを応答の本文に出す。
  識別子やテーブル名が利用者へ漏れ、404 で存在を隠せない。
- `ConflictException` を捕まえて同じトランザクションで処理を続ける。
  `23505` の後は PostgreSQL のトランザクションが中断状態になり、後の SQL がすべて失敗する。
- Spring と jOOQ の例外（`DuplicateKeyException`、`CannotAcquireLockException`）を Repository の外へ漏らす。
  Application と Domain が永続化の技術に依存し、HTTP では 500 になる。

## 作成時のチェックリスト

- [ ] 業務上の失敗は `NotFoundException`、`BusinessRuleViolationException`、`ConflictException` のどれかで投げる。［自分で点検］
- [ ] `NoSuchElementException` を生成せず、引数なしの `Optional.orElseThrow()` を呼ばない。［ArchUnit で検査：GeneralCodingRulesArchTest.noSuchElementExceptionIsNotThrown］
- [ ] `Throwable`、`Exception`、`RuntimeException`、`Error` を投げない。［ArchUnit で検査：GeneralCodingRulesArchTest.noClassesShouldThrowGenericExceptions］
- [ ] `shared` の型は Spring Web、Spring の HTTP、Servlet の型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.sharedModuleDoesNotDependOnHttp］
- [ ] 集約ごとの例外の型を作らない。［自分で点検］
- [ ] メッセージは英語にし、対象の識別子を含める。［自分で点検］
- [ ] Controller に try と catch を書かない。［自分で点検］
- [ ] Repository の実装は Spring と jOOQ の例外を外へ出さず、出すときは原因に付けた `ConflictException` に変える。［自分で点検］
- [ ] 単体テストで例外の型を確かめる。［自分で点検］
