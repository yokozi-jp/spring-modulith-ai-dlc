---
type: Convention
title: クラスの役割：業務上の失敗の例外
description: 業務上の失敗を表す例外（NotFoundException、BusinessRuleViolationException、ConflictException）と 422 の理由の enum（FailureReason）の定義、置き場所、HTTP のステータスと problem type、ログレベル、Listener での扱い、例、テスト、アンチパターン、作成時のチェックリストを定める。見つからない、業務規則の違反、競合を例外で表すとき、422 の理由を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：業務上の失敗の例外

業務上の失敗は、`shared.failure` の `NotFoundException`（404）と `BusinessRuleViolationException`（422）、`shared.concurrency` の `ConflictException`（409）の三つの型だけで投げる。
422 は理由をモジュールの `FailureReason` の enum の定数で渡し、409 は競合の種類で、どちらも理由ごとの problem type と `title` になる。
集約ごとの型を作らず、JDK の例外と Spring の HTTP の例外を業務上の失敗に使わない。
HTTP のステータス、本文、ログレベルは `error` モジュールの `ApiExceptionHandler` が決め、Controller は try と catch を書かない。
決定理由は [ADR-062](../../adr/ADR-062-map-business-exceptions-to-404-409-422.md) と [ADR-127](../../adr/ADR-127-derive-problem-types-from-failure-reasons.md) に示す。

## 定義

利用者の要求が業務の上で成り立たないとき、Domain、Application、Infrastructure は、その失敗を例外で呼び出し側へ伝える。
**業務上の失敗の例外**は、利用者が要求か状態を見直せば解消しうる失敗を表す、次の三つの型である。

- **NotFoundException**：ユースケースが対象にする集約や行が存在しない。
  HTTP API では、要求のパスで指定したリソースにあたる。
  参照の権限がなく存在を隠す場合も同じ型を投げる。
- **BusinessRuleViolationException**：許されない状態遷移、業務規則の違反、要求を今の状態へ適用できない。
  要求が参照する別の集約や値が存在しない場合も、この型を投げる。
  理由を `FailureReason` の enum の定数で渡す。
- **ConflictException**：版の不一致、行ロックの失敗、一意制約の違反。
  読み直せば解消しうる。

プログラムの誤り（主キーの条件が 2 行に合う、束縛すべき値がない）は業務上の失敗ではない。
`IllegalStateException` と `IllegalArgumentException` で投げ、HTTP の 500 にする。

## 例外と値の使い分け

要求を断り、トランザクションで書いたものを取り消す失敗は、業務上の失敗の例外で投げる。
業務の状態に記録して正常に終える結果（決済の拒否、契約の不備、請求しない、処理済み）は、[Result](result.md) の値で返す。
場合が閉じていれば、Result を sealed interface と record で表す。

迷ったら、失敗の前に書いた行とイベントの出版を commit してよいかで決める。
commit してよければ値で返す。
値を返すと Spring は commit し、Spring Modulith は出版を `COMPLETED` にする。
commit してはいけなければ例外を投げる。
例外は Spring のトランザクションの rollback、イベント出版の取り消し、`ApiExceptionHandler` の変換、出版の `FAILED` の合図になる。

値の失敗を rollback させるために `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` を呼ばない。
rollback させたい失敗は、例外にする。

この基準は[非同期処理の失敗時の再試行と回復](../../integration/async-failure-recovery.md)の「業務の状態に記録してリスナーを正常終了させる」と同じである。
検査例外、自作の `Result`、`ErrorResponse` を実装した例外を採らない理由は [ADR-062](../../adr/ADR-062-map-business-exceptions-to-404-409-422.md) の Alternatives Considered に示す。

## 置き場所と命名

- `NotFoundException` と `BusinessRuleViolationException` は `com.example.demo.shared.failure` に、`ConflictException` は `com.example.demo.shared.concurrency` に置く。
- 三つの型は Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。
- 新しい型を足さない。
  四つ目の型が要るときは ADR で決める。
- 集約ごとの型（`OrderNotFoundException`）を作らない。
  三つの型は `final` であり、継承できない。

## 422 の理由の enum

- `BusinessRuleViolationException` は、理由とログ用のメッセージを受け取る。
  メッセージだけのコンストラクタはない。
- 理由は、`shared.failure.FailureReason` を実装する enum の定数で表す。
  enum はモジュールごとに、理由を投げる層のパッケージに置く。
  集約と Domain Service が投げる理由は `<module>.domain.model` に置く。
  Presentation だけが投げる理由は `<module>.presentation.web` に置く（Presentation は Domain に依存できない）。
- enum の名前は `<対象>Failure`（`OrderFailure`、`TenantFailure`、`TenantSelectionFailure`）にし、定数は理由を表す名前にする（`NOT_DRAFT`、`PRODUCT_NOT_FOUND`）。
- enum は `com.example.demo.<module>` の下に置き、type と `title` のキーを書かない。
  `error` モジュールの `ProblemTypes` が、type `/problems/<module>/<定数の kebab-case>` と `title` のキー `problem.title.<module>/<定数の kebab-case>` を作る。
  `<module>` は enum のパッケージから決まる。
- 定数を足したら、`messages.properties` と `messages_ja.properties` にその `title` を足し、`ProblemTypesTest` の type の一覧に 1 行を足す。
- 定数の名前を変えるか、enum を別のモジュールへ移すと type が変わり、API の契約を壊す。
- テストが業務規則の違反を起こすときは、プロダクションコードの理由を借りず、`com.example.demo` の外の `errorfixture.presentation.web.ErrorFixtureFailure` を渡す。
  `com.example.demo` の下のテストの enum は、type の一覧と、テストが書き出す OpenAPI に入る。
- 409 の `ConflictException` には理由の enum を渡さない。
  type は `ConflictException.Kind` から決まり、モジュールによらず共通である。

## 必須の記述

- メッセージは開発者向けの英語にし、対象と識別子を `order not found: orderId=...` の形で含める。
  メッセージは応答の本文に出ず、ログにだけ出る。
- `Optional` からは `orElseThrow(() -> new NotFoundException("order not found: orderId=" + orderId))` で取り出す。
- 集約の状態遷移と Domain Service の業務規則の違反は `BusinessRuleViolationException` で投げる。
- Repository の実装は、Spring と jOOQ の例外（`CannotAcquireLockException`、`DuplicateKeyException`）を Repository の外へ出さず、自分で catch して変えることもしない。
  書き込みは `TableWriter` が、SQLSTATE で見分けて、原因を付けずに種類を付けた `ConflictException` に変える（[jOOQ の Repository](jooq-repository.md)）。
  種類は、版の不一致が `ConflictException.Kind.VERSION`、行ロックの失敗（`55P03`）が `Kind.LOCK`、一意制約の違反（`23505`）が `Kind.UNIQUE` である。
  同じ `Idempotency-Key` の要求の終わりを待ち切れなかった失敗（キーの行の `55P03`）は、`shared.persistence` の `IdempotencyKeyWriter` が `Kind.IDEMPOTENCY_IN_PROGRESS` にする（[ADR-126](../../adr/ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md)）。
- 三つの型は原因（cause）を持たない。
  コンストラクタは原因を受け取らず、後から `initCause` を呼ぶと `IllegalStateException` になる。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型。
- **依存してはいけない型**：Spring Web、Spring の HTTP、Servlet の型（`ResponseStatusException`、`HttpStatus`）。
  `shared` の型が HTTP に依存すると、Domain が HTTP の境界に結びつく。

## 例外、HTTP、ログ、Listener の対応

| 例外                                                                                            | HTTP | 本文                                                                                                      | API のログ                                                                  | 非同期の Listener            |
| ----------------------------------------------------------------------------------------------- | ---- | --------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- | ---------------------------- |
| `NotFoundException`                                                                             | 404  | `about:blank`、`title` は `problem.title.404`、`detail` なし                                              | INFO、例外付き（原因なし）、`problem.type`、`API business failure`          | 回復不能として再試行しない   |
| `BusinessRuleViolationException`                                                                | 422  | 理由の `/problems/<module>/<kebab-case>`、`title` は `problem.title.<module>/<kebab-case>`、`detail` なし | INFO、例外付き（原因なし）、`problem.type`、`API business failure`          | 回復不能として再試行しない   |
| `ConflictException`                                                                             | 409  | 種類の `/problems/<kebab-case>`、`title` は `problem.title.<kebab-case>`、`detail` なし                   | INFO、例外付き（原因なし）、`problem.type`、`conflict.kind`、`API conflict` | 回復不能として再試行しない   |
| JDK の例外ほか（`IllegalStateException`、`IllegalArgumentException`、`NoSuchElementException`） | 500  | `about:blank`、`title` は `problem.title.500`、`detail` なし                                              | ERROR、例外と原因の連鎖付き、`Unhandled API exception`                      | 予期しない例外として送出する |

`title` は `Accept-Language` から解決した言語で返り、404 の本文は対象がない場合と存在を隠す場合で同じになる。
409 の type は、`Kind.VERSION` が `/problems/version-conflict`、`Kind.LOCK` が `/problems/lock-timeout`、`Kind.UNIQUE` が `/problems/unique-violation`、`Kind.IDEMPOTENCY_IN_PROGRESS` が `Retry-After` 付きの `/problems/idempotency-key-in-progress` である。
Listener の扱いは[非同期処理の失敗時の再試行と回復](../../integration/async-failure-recovery.md)の「Spring Modulith のイベントの失敗」に合わせる。
業務上の失敗の三つの型は、自動で再試行しない。
ステータス管理テーブル（[非同期処理のステータス管理](../../integration/async-job-status.md)）を持つ処理は FAILED と失敗の内容を記録して正常終了し、持たない処理は `on` から送出してイベント出版を `FAILED` で残す（レジストリが DLQ を兼ねる）。
ステータス管理テーブルを持たない処理では、`ConflictException` で `FAILED` のまま残ったイベント出版を運用者が `FailedEventPublications` で再投入すると、読み直して成功する見込みがある。

`23505` と `55P03` の Spring の例外のメッセージには SQL が入り、`23505` では重複したキーの値も入る。
三つの型は原因を持たないため、これらの SQL と値は INFO のログに出ない（[可観測性の規約](../../observability/conventions.md)の「発生源で渡さない値」）。
409 の `conflict.kind` は `version`、`lock`、`unique`、`idempotency_in_progress` のどれかで、どの競合かをログで区別する。
404、409、422 のログの `problem.type` は、応答の type と同じ値である。

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
理由は、同じ `domain.model` の `OrderFailure` の定数で渡す。

```java
public enum OrderFailure implements FailureReason {
  /** 下書きでない注文を変更、確定、取消しようとした。 */
  NOT_DRAFT,
  /** 存在しない商品を指定した。 */
  PRODUCT_NOT_FOUND
}
```

```java
private void ensureDraft() {
  if (status != OrderStatus.DRAFT) {
    throw new BusinessRuleViolationException(
        OrderFailure.NOT_DRAFT, "order is not draft: orderId=" + id.value() + ", status=" + status);
  }
}
```

`NOT_DRAFT` の 422 は、type が `/problems/ordering/not-draft`、`title` が `messages_ja.properties` の `problem.title.ordering.not-draft` になる。

Controller は参照の結果がなければ `NotFoundException` を投げ、ステータスを自分で決めない（[Controller](controller.md)）。

```java
@GetMapping("/{orderId}")
/* package */ OrderDetailsResponse details(@PathVariable final UUID orderId) {
  return orderQueries
      .findDetails(orderId.toString())
      .map(OrderDetailsResponse::from)
      .orElseThrow(() -> new NotFoundException("order not found: orderId=" + orderId));
}
```

## 対応するテスト

集約と Domain Service の単体テストで、投げる例外の型、理由、メッセージの識別子を確かめる（`isInstanceOfSatisfying(BusinessRuleViolationException.class, exception -> assertThat(exception.reason()).isEqualTo(OrderFailure.NOT_DRAFT))`）。
値オブジェクトの単体テストは、`IllegalArgumentException` を確かめる。
例外から HTTP のステータス、本文、ログへの対応づけは `ApiErrorContractTest` と `ApiExceptionHandlerTest` が確かめるため、機能ごとのテストでは確かめない。
type の一覧と、すべての type に日本語と英語の `title` があることは `ProblemTypesTest` が、OpenAPI の `ProblemDetail.type` に type が載ることは `OpenApiContractTest` が確かめる。

## アンチパターン

- 集約ごとの例外の型（`OrderNotFoundException`）を作る。
  `ApiExceptionHandler` が知る型が集約の数だけ増える。
- 見つからないことや業務規則の違反を `NoSuchElementException`、`IllegalStateException` で投げる。
  HTTP の 500 になり、プログラムの誤りと区別できない。
- `ResponseStatusException` を投げる、または `@ResponseStatus` を付ける。
  Domain と `shared` が HTTP の型に依存し、`@ResponseStatus` は `@ExceptionHandler(Exception.class)` が先に受けて効かない。
- モジュールで `@RestControllerAdvice` を置くか `ProblemDetail` を作り、失敗を自分で応答に変える。
  type と `title` が `ApiExceptionHandler` の対応づけと食い違い、ログの記録が二重になる。
- 別々の理由に同じ定数（`INVALID` など）を使う。
  画面が理由を区別できず、`title` が理由を示さない。
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
- [ ] 値オブジェクトの record は `BusinessRuleViolationException` を投げず、不変条件の違反を `IllegalArgumentException` で表す。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.valueObjectsDoNotThrowBusinessRuleViolation］
- [ ] `NoSuchElementException` を生成せず、引数なしの `Optional.orElseThrow()` を呼ばない。［ArchUnit で検査：GeneralCodingRulesArchTest.noSuchElementExceptionIsNotThrown］
- [ ] `Throwable`、`Exception`、`RuntimeException`、`Error` を投げない。［ArchUnit で検査：GeneralCodingRulesArchTest.noClassesShouldThrowGenericExceptions］
- [ ] `shared` と各モジュールの `domain`、`application`、`infrastructure.persistence` の型は、Spring Web、Spring の HTTP、Servlet の型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.httpTypesAreNotUsedByInnerLayers］
- [ ] `error.presentation.web` の外で、`ProblemDetail`、`ErrorResponse`、`ErrorResponseException`、`ResponseStatusException`、`@ExceptionHandler`、`@ControllerAdvice`、`@RestControllerAdvice`、`@ResponseStatus`、`HttpStatus.CONFLICT`、`HttpStatus.UNPROCESSABLE_CONTENT` を使わない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.httpErrorsAreMappedOnlyInErrorPresentationWeb］
- [ ] 409 と 422 の status を整数（`sendError(422)`、`ResponseEntity.status(409)`）で書かない。［自分で点検］
- [ ] 422 の理由は、モジュールの `FailureReason` の enum の定数で渡す。［型で検査：`BusinessRuleViolationException` は理由の enum の定数を受け取るコンストラクタしか持たない］
- [ ] `FailureReason` は `com.example.demo.<module>` の下の enum で実装する。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.failureReasonsAreModuleEnums］
- [ ] 理由の定数ごとに日本語と英語の `title` を足す。［テストで検査：ProblemTypesTest］
- [ ] 理由の定数の名前を変えず、enum を別のモジュールへ移さない。［テストで検査：ProblemTypesTest の type の一覧］
- [ ] 集約ごとの例外の型を作らない。［自分で点検］
- [ ] メッセージは英語にし、対象の識別子を含める。［自分で点検］
- [ ] Controller に try と catch を書かない。［自分で点検］
- [ ] Repository の実装は Spring と jOOQ の例外を外へ出さず、出すときは原因を付けずに種類を付けた `ConflictException` に変える。［自分で点検］
- [ ] 三つの型に原因を付けない（`initCause` を呼ばない）。［実行時に検査：`initCause` は `IllegalStateException` になる］
- [ ] 単体テストで例外の型を確かめる。［自分で点検］
- [ ] 失敗の前に書いた行とイベントの出版を commit してよいときは、例外を投げず Result の値で返す。［自分で点検］
- [ ] 値の失敗を `setRollbackOnly()` で rollback させない。［自分で点検］
