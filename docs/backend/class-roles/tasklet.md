---
type: Convention
title: クラスの役割：Tasklet
description: 機能モジュールの batch に置き、chunk にしない 1 単位の処理で CommandHandler を 1 つだけ呼ぶ <Job>Tasklet の定義、置き場所と命名、必須の記述（1 単位の handle、冪等、トランザクションを持たないこと）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める規約で、Job の中で件数を数えずに 1 回だけ行う処理を作るときに読む。
tags: [convention, backend, class-role, batch]
---

# クラスの役割：Tasklet

`<Job>Tasklet` は、chunk にしない 1 単位の処理として、自分のモジュールの CommandHandler を 1 つだけ呼ぶ Spring Batch の Tasklet であり、Job を持つ機能モジュールの `batch` に package-private で置く。
トランザクションは CommandHandler の `handle` が開き、Tasklet はトランザクションを持たない。
Job の規則は [バッチ](../../batch/index.md) に、役割の決定理由は [ADR-115](../../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 定義

Job には、行を 1 件ずつ読んで書くのでなく、1 回だけ行う処理がある（期間の集計の行を作る、配信のファイルを作って置く、など）。
**Tasklet**（`<Job>Tasklet`）は、その 1 単位から Command を作り、1 つのユースケースを始めるクラスである。
行を 1 件ずつ処理するときは、Tasklet でなく [ItemReader](item-reader.md) と [ItemWriter](item-writer.md) の chunk にする。

Tasklet は業務処理の置き場所ではない。
業務規則と保存は、呼び出す [CommandHandler](command-handler.md) が担う。

## 置き場所と命名

- Job を持つ機能モジュールの `com.example.demo.<module>.batch` に置く。
- 名前は Job の名前の JobName に `Tasklet` を付ける（`SummarizeMonthTasklet`）。
  1 つの Job に Tasklet が 2 つ以上あるときは、JobName の後に Step の役割を付ける（`SummarizeMonthExportTasklet`）。

## 必須の記述

- `@Component` を付けた package-private の `class` にし、`Tasklet` を実装する。
- `execute` は、JobParameters と partition の値から Command を作り、CommandHandler の `handle` を 1 回呼んで `RepeatStatus.FINISHED` を返す。
- 呼ぶ CommandHandler は 1 つだけにする。
- `@Transactional` を付けない。
- 呼ぶ CommandHandler を冪等にする。
  `handle` が commit した後、Step の状態を保存する前に落ちると、再開で同じ `execute` がもう一度呼ばれる。
  書き方は [冪等性と再開](../../batch/idempotency-and-restart.md#冪等な書き方) に従う。
- テナントを自分で束縛しない。
  `execute` の呼び出しの間は、Aspect が partition のテナントを束縛する（[トランザクション、ロール、テナント](../../batch/transaction-and-tenant.md#テナントごとの-partition)）。
- クラス、フィールド、コンストラクタ、`execute` に Javadoc を書く。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、Spring Batch の `Tasklet`、`StepContribution`、`ChunkContext`、`RepeatStatus`、自分のモジュールの `application` の Command、Result、CommandHandler 1 つ。
- **依存してはいけない型**：2 つ目の CommandHandler、Repository、`TableWriter`、`DSLContext`、Domain の型、`PlatformTransactionManager`、`TransactionTemplate`。

## 最小の例

```java
// com.example.demo.ordering.batch.SummarizeMonthTasklet（抜粋）
@Override
public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
  final String period = chunkContext.getStepContext().getJobParameters().get("period").toString();
  summarizeMonth.handle(new SummarizeMonthCommand(period));
  return RepeatStatus.FINISHED;
}
```

## 対応するテスト

Tasklet は、Job を起動する結合テストで確かめる。
同じ期間を 2 回流したときと、`handle` の後で落としたときの再開で、結果が 1 回だけ流したときと同じになることを含める（[バッチのテスト観点](../../batch/testing.md)）。

## アンチパターン

- Tasklet に業務処理を書く、または Repository、`TableWriter`、`DSLContext` を直接使う。
- Tasklet の中で行を 1 件ずつ読んで、ループで CommandHandler を呼ぶ。
  途中で落ちたときの再開の位置がなく、件数が JobRepository に残らない。
  chunk の ItemReader と ItemWriter にする。
- `RepeatStatus.CONTINUABLE` を返して、同じ `execute` を繰り返す。
- 1 つの `execute` から複数の CommandHandler を呼ぶ。

## 作成時のチェックリスト

- [ ] `<module>.batch` に `<JobName>Tasklet` として置く。［自分で点検］
- [ ] 1 回の `execute` で CommandHandler を 1 つだけ 1 回呼ぶ。［自分で点検］
- [ ] Repository、`TableWriter`、`DSLContext` を使わない。［自分で点検］
- [ ] `@Transactional` を付けない。［自分で点検］
- [ ] 呼ぶ CommandHandler が冪等で、2 回流すテストがある。［自分で点検］
- [ ] テナントを自分で束縛しない。［自分で点検］
