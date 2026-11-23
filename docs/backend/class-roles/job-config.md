---
type: Convention
title: クラスの役割：Job の設定
description: 機能モジュールの batch に置き、Spring Batch の Job と Step を Bean で定義する <Job>JobConfig の定義、置き場所と命名（Job と Step の名前）、必須の記述（期間の規則、Step のトランザクション、partition、retry）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める規約で、業務のバッチの Job を作るときや Step の構成を変えるときに読む。
tags: [convention, backend, class-role, batch]
---

# クラスの役割：Job の設定

`<Job>JobConfig` は、1 つの Spring Batch の Job と、その Step を Bean で定義する `@Configuration` であり、Job を持つ機能モジュールの `batch` に package-private で置く。
Step には `transactionManager` と `transactionAttribute` を渡さず、業務の書き込みを ItemWriter と Tasklet が呼ぶ CommandHandler に任せる。
Job の規則は [バッチ](../../batch/index.md) に、役割の決定理由は [ADR-115](../../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 定義

業務のバッチでは、どの Step をどの順に流し、何件ずつ処理し、どの失敗を retry するかを、どこか 1 か所で決める必要がある。
**Job の設定**（`<Job>JobConfig`）は、1 つの Job について、Job、Step、期間の規則、partition、retry の方針を Bean で組み立てるクラスである。

Job の設定は業務処理の置き場所ではない。
業務規則と保存は、[ItemWriter](item-writer.md) と [Tasklet](tasklet.md) が呼ぶ [CommandHandler](command-handler.md) が担う。
読む行の選び方は [ItemReader](item-reader.md) が担う。

## 置き場所と命名

- Job を持つ機能モジュールの `com.example.demo.<module>.batch` に置く（`com.example.demo.ordering.batch`）。
- クラスの名前は、Job の名前の JobName に `JobConfig` を付ける（`CloseMonthJobConfig`）。
- Job の名前は `<module>.<JobName>` にする（`ordering.CloseMonth`）。
  JobName は、業務の動詞から始まるパスカルケースにする。
  起動のときに `spring.batch.job.name` に渡す名前で、設計書の識別子でもある（[機能の設計書](../../writing/design-doc-feature-specs.md)）。
- Step の名前は、Job の名前に `.` と Step の役割を付ける（`ordering.CloseMonth.close`）。
- 1 つの Job に 1 つの `<Job>JobConfig` を作る。

## 必須の記述

- `@Configuration(proxyBeanMethods = false)` を付けた package-private の `class` にする。
- Job と Step を `@Bean` のメソッドで定義する。
- Step に `transactionManager` と `transactionAttribute` を渡さない（[トランザクション、ロール、テナント](../../batch/transaction-and-tenant.md#トランザクションとロール)）。
- `JobParametersValidator` で必須の引数を検査する（[ジョブの入口と起動](../../batch/job-entry.md#jobparameters-と入力の検査)）。
  期間で区切る Job は `period` を必須にする。
- 期間で区切る Job は、`shared.batch` の `JobPeriodRule` を同じクラスの `@Bean` で定義する（[定時起動と追いつき](../../batch/scheduling-and-catch-up.md#期間の規則)）。
- テナントの業務の表を書く Step は、テナントごとの partition にする（[トランザクション、ロール、テナント](../../batch/transaction-and-tenant.md#テナントごとの-partition)）。
- retry は、[冪等性と再開](../../batch/idempotency-and-restart.md#retry-と失敗) の方針で `faultTolerant()` と `retryPolicy` に書き、`skipPolicy` を付けない。
- `JobParametersIncrementer` を付けない。
- クラスと `@Bean` のメソッドに Javadoc を書く。

## 依存してよい型、してはいけない型

- **依存してよい型**：Spring Batch の Job、Step、builder、`JobRepository`、同じ `batch` の ItemReader、ItemWriter、Tasklet、`shared.batch` の `JobPeriodRule`、`tenancy` の `<Feature>Queries`（partition の一覧を作るとき）。
- **依存してはいけない型**：CommandHandler、Repository、`TableWriter`、`DSLContext`、`PlatformTransactionManager`、Presentation と Infrastructure の型。

## 最小の例

次は、テナントごとの partition で月次の締めを流す Job の設定の抜粋である。

```java
// com.example.demo.ordering.batch.CloseMonthJobConfig（抜粋）
@Bean
Job closeMonthJob(final JobRepository jobRepository, final Step closeMonthByTenant) {
  return new JobBuilder("ordering.CloseMonth", jobRepository)
      .validator(new DefaultJobParametersValidator(new String[] {"period"}, new String[0]))
      .start(closeMonthByTenant)
      .build();
}

@Bean
JobPeriodRule closeMonthPeriods() {
  return new JobPeriodRule(
      "ordering.CloseMonth", LocalDate.of(2026, 12, 1), Period.ofMonths(1), Duration.ofHours(1));
}

@Bean
Step closeMonthByTenant(
    final JobRepository jobRepository, final TenancyQueries tenancy, final Step closeMonth) {
  return new StepBuilder("ordering.CloseMonth.byTenant", jobRepository)
      .partitioner("ordering.CloseMonth.close", gridSize -> byTenant(tenancy))
      .step(closeMonth)
      .build();
}

@Bean
Step closeMonth(
    final JobRepository jobRepository,
    final CloseMonthItemReader reader,
    final CloseMonthItemWriter writer) {
  // transactionManager を渡さない。書き込みのトランザクションは CommandHandler の handle が開く。
  return new StepBuilder("ordering.CloseMonth.close", jobRepository)
      .<String, String>chunk(100)
      .reader(reader)
      .writer(writer)
      .build();
}
```

## 対応するテスト

Job の設定は、Job を起動する結合テストで確かめる。
観点は [バッチのテスト観点](../../batch/testing.md) に従う。

## アンチパターン

- Step に `transactionManager` か `transactionAttribute` を渡し、`handle` を chunk のトランザクションに加える（[ADR-115](../../adr/ADR-115-build-business-batches-with-spring-batch.md) の選択肢3）。
- Job や Step を `@Bean` にせず、メソッドの中で組み立てて起動する。
- Job の設定に業務の条件分岐を書く。
- `JobParametersIncrementer` を付ける。
- `skipPolicy` を付けて、失敗した行を飛ばす。
- partition の名前を連番にする。

## 作成時のチェックリスト

- [ ] `<module>.batch` に `<JobName>JobConfig` として置く。［自分で点検］
- [ ] Job の名前を `<module>.<JobName>` にする。［自分で点検］
- [ ] Job と Step を `@Bean` で定義する。［自分で点検］
- [ ] Step に `transactionManager` と `transactionAttribute` を渡さない。［自分で点検］
- [ ] `JobParametersValidator` で必須の引数を検査する。［自分で点検］
- [ ] 期間で区切る Job は、`JobPeriodRule` を `@Bean` で定義する。［自分で点検］
- [ ] テナントの業務の表を書く Step を、テナントの `public_id` を名前にした partition にする。［自分で点検］
- [ ] `JobParametersIncrementer` と `skipPolicy` を付けない。［自分で点検］
- [ ] Job を起動する結合テストがある。［自分で点検］
