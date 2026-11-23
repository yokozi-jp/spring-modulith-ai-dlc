---
type: Convention
title: クラスの役割：ItemReader
description: 機能モジュールの batch に置き、Job が処理する行を自分のモジュールの <Feature>Queries から読む <Job>ItemReader の定義、置き場所と命名、必須の記述（process indicator、キーの順の読み方、位置を保存しないこと）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める規約で、Job が DB から読む行の選び方を作るときに読む。
tags: [convention, backend, class-role, batch]
---

# クラスの役割：ItemReader

`<Job>ItemReader` は、Job が処理する行を、自分のモジュールの `<Feature>Queries` から決まった件数ずつ読む Spring Batch の ItemReader であり、Job を持つ機能モジュールの `batch` に package-private で置く。
業務の行の状態の列で未処理の行だけを選ぶ process indicator にし、読んだ位置を ExecutionContext に保存しない。
Job の規則は [バッチの冪等性と再開](../../batch/idempotency-and-restart.md) に、役割の決定理由は [ADR-115](../../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 定義

DB から読む Job は、再開したときに、どの行から読み直すかを決める必要がある。
**ItemReader**（`<Job>ItemReader`）は、未処理の行のキーを、業務のキーの順に決まった件数ずつ読み、1 件ずつ Step に渡すクラスである。
処理済みかどうかは業務の行の状態が表すので、再開すると未処理の行を先頭から読み直せばよく、読んだ位置を保存しない。

ItemReader は DB に直接触れない。
`<Feature>Queries` を実装する [QueryService](query-service.md) が、名前のある読み取り専用のトランザクションで読み、モジュールのロールとテナントが効く。

## 置き場所と命名

- Job を持つ機能モジュールの `com.example.demo.<module>.batch` に置く。
- 名前は Job の名前の JobName に `ItemReader` を付ける（`CloseMonthItemReader`）。
- 読む行のキーと条件を、自分のモジュールの [参照のインタフェース](feature-queries.md) のメソッドにする（`OrderQueries#findUnclosedOrderIds`）。

## 必須の記述

- `@Component` と `@StepScope` を付けた package-private の `class` にし、`ItemReader<String>` を実装する。
  `@StepScope` で、partition の Step の実行ごとに新しい reader になる。
- `read` は、手元の件数を使い切ったら `<Feature>Queries` で次の件数を読み、なくなれば `null` を返す。
- `<Feature>Queries` のメソッドは、未処理の行を業務のキーの昇順に、前に読んだキーより後から、決まった件数だけ返す。
- `ItemStream` を実装せず、読んだ位置を ExecutionContext に保存しない。
- 読んだ行の全件をメモリに載せない。
- テナントを自分で束縛しない。
  `read` の呼び出しの間は、Aspect が partition のテナントを束縛する（[トランザクション、ロール、テナント](../../batch/transaction-and-tenant.md#テナントごとの-partition)）。
- クラス、フィールド、コンストラクタ、`read` に Javadoc を書く。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、Spring Batch の `ItemReader`、`@StepScope`、自分のモジュールの `<Feature>Queries`。
- **依存してはいけない型**：Spring Batch の JDBC の reader（`JdbcCursorItemReader`、`JdbcPagingItemReader`）、`DataSource`、`JdbcTemplate`、`DSLContext`、Repository、CommandHandler、ほかのモジュールの `<Feature>Queries`。

## 最小の例

```java
// com.example.demo.ordering.batch.CloseMonthItemReader（抜粋）
@Override
public @Nullable String read() {
  if (buffer.isEmpty()) {
    buffer.addAll(orderQueries.findUnclosedOrderIds(period, lastOrderId, PAGE_SIZE));
    if (buffer.isEmpty()) {
      return null;
    }
  }
  lastOrderId = buffer.removeFirst();
  return lastOrderId;
}
```

## 対応するテスト

ItemReader は、Job を起動する結合テストで確かめる。
未処理の行だけを読むこと、再開で未処理の行を読み直すことを含める（[バッチのテスト観点](../../batch/testing.md)）。

## アンチパターン

- Spring Batch の JDBC の reader で、業務のスキーマを読む（理由は [ADR-115 の Decision](../../adr/ADR-115-build-business-batches-with-spring-batch.md#decision)）。
- `infrastructure.persistence` に reader を置き、jOOQ で読む。
- 処理済みの列で絞らず、読んだ位置を ExecutionContext に保存して再開する（理由は [ADR-115 の Decision](../../adr/ADR-115-build-business-batches-with-spring-batch.md#decision)）。
- 未処理の行を毎回先頭から読み、前に読んだキーより後から読まない。

## 作成時のチェックリスト

- [ ] `<module>.batch` に `<JobName>ItemReader` として置く。［自分で点検］
- [ ] 自分のモジュールの `<Feature>Queries` だけで読む。［自分で点検］
- [ ] 未処理の行を業務のキーの昇順に、前に読んだキーより後から読む。［自分で点検］
- [ ] 読んだ位置を ExecutionContext に保存しない。［自分で点検］
- [ ] テナントを自分で束縛しない。［自分で点検］
- [ ] Job を起動する結合テストで、未処理の行だけを読むことを確かめる。［自分で点検］
