---
type: Convention
title: クラスの役割：ItemWriter
description: 機能モジュールの batch に置き、chunk の 1 件ごとに CommandHandler を 1 つだけ呼ぶ <Job>ItemWriter の定義、置き場所と命名、必須の記述（1 件ごとの handle、冪等、トランザクションを持たないこと）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める規約で、Job の chunk の書き込みを作るときに読む。
tags: [convention, backend, class-role, batch]
---

# クラスの役割：ItemWriter

`<Job>ItemWriter` は、chunk の 1 件ごとに Command を作り、自分のモジュールの CommandHandler を 1 つだけ呼ぶ Spring Batch の ItemWriter である。
Job を持つ機能モジュールの `batch` に package-private で置く。
トランザクションは CommandHandler の `handle` が 1 件ごとに開き、ItemWriter と Step はトランザクションを持たない。
Job の規則は [バッチの冪等性と再開](../../batch/idempotency-and-restart.md) に、役割の決定理由は [ADR-115](../../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 定義

chunk の書き込みを業務のユースケースにつなぐには、Spring Batch の Step と CommandHandler の間に入口が要る。
**ItemWriter**（`<Job>ItemWriter`）は、chunk の 1 件から Command を作り、1 つのユースケースを始めるクラスである。
[Listener](listener.md) がイベントから Command を作って CommandHandler を 1 つ呼ぶのと同じ形である。

ItemWriter は業務処理の置き場所ではない。
業務規則と保存は、呼び出す [CommandHandler](command-handler.md) が担う。

## 置き場所と命名

- Job を持つ機能モジュールの `com.example.demo.<module>.batch` に置く。
- 名前は Job の名前の JobName に `ItemWriter` を付ける（`CloseMonthItemWriter`）。
- 呼ぶ CommandHandler は、業務の動詞で名付ける（`CloseOrderCommandHandler`）。

## 必須の記述

- `@Component` を付けた package-private の `class` にし、`ItemWriter<String>` を実装する。
- `write` は、chunk の 1 件ごとに Command を作り、CommandHandler の `handle` を 1 回呼ぶ。
- 呼ぶ CommandHandler は 1 つだけにする。
- `@Transactional` を付けない。
  `handle` が自分の名前のトランザクションを開き、モジュールのロールとテナントを設定する。
- 呼ぶ CommandHandler を冪等にする。
  再開と retry で、commit 済みの行がもう一度 `write` に渡る。
  書き方は [冪等性と再開](../../batch/idempotency-and-restart.md#冪等な書き方) に従う。
- テナントを自分で束縛しない。
  `write` の呼び出しの間は、Aspect が partition のテナントを束縛する（[トランザクション、ロール、テナント](../../batch/transaction-and-tenant.md#テナントごとの-partition)）。
- 業務上の失敗の例外を捕まえない。
  retry と失敗の扱いは Job の設定で決める（[冪等性と再開](../../batch/idempotency-and-restart.md#retry-と失敗)）。
- クラス、フィールド、コンストラクタ、`write` に Javadoc を書く。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、Spring Batch の `ItemWriter` と `Chunk`、自分のモジュールの `application` の Command、Result、CommandHandler 1 つ。
- **依存してはいけない型**：2 つ目の CommandHandler、Repository、`TableWriter`、`DSLContext`、Domain の型、`PlatformTransactionManager`、`TransactionTemplate`。

## 最小の例

```java
// com.example.demo.ordering.batch.CloseMonthItemWriter（抜粋）
@Override
public void write(final Chunk<? extends String> orderIds) {
  for (final String orderId : orderIds) {
    closeOrder.handle(new CloseOrderCommand(orderId));
  }
}
```

## 対応するテスト

ItemWriter は、Job を起動する結合テストで確かめる。
途中で落とした後の再開で、もう一度渡った行の結果が 1 回だけ流したときと同じになることを含める（[バッチのテスト観点](../../batch/testing.md)）。

## アンチパターン

- ItemWriter に業務処理を書く、または Repository、`TableWriter`、`DSLContext` を直接使う。
  `*_pgm_cd` が束縛されず、共通カラムの登録が失敗する。
- chunk 全体を 1 つの Command にして、1 回の `handle` に渡す。
  1 件の失敗で chunk 全体がロールバックし、retry の単位が chunk になる。
  まとめるのは、1 件ごとの commit が遅いと測ったときだけにする。
- 1 つの `write` から複数の CommandHandler を呼ぶ。
- 呼ぶ CommandHandler が冪等でなく、再開で同じ行を 2 回処理する。

## 作成時のチェックリスト

- [ ] `<module>.batch` に `<JobName>ItemWriter` として置く。［自分で点検］
- [ ] 1 件ごとに CommandHandler を 1 つだけ呼ぶ。［自分で点検］
- [ ] Repository、`TableWriter`、`DSLContext` を使わない。［自分で点検］
- [ ] `@Transactional` を付けない。［自分で点検］
- [ ] 呼ぶ CommandHandler が冪等で、再開のテストがある。［自分で点検］
- [ ] テナントを自分で束縛しない。［自分で点検］
