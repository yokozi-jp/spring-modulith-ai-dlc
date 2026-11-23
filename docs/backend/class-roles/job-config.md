---
type: Convention
title: クラスの役割：Job の設定
description: 機能モジュールの batch に置き、Spring Batch の Job と Step を Bean で定義する <Job>JobConfig の定義、置き場所と命名、必須の記述（Job の名前、Step のトランザクション、partition、skip と retry）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。業務のバッチの Job を作るとき、Step の構成を変えるときに読む。
tags: [convention, backend, class-role, batch]
---

# クラスの役割：Job の設定

`<Job>JobConfig` は、1 つの Spring Batch の Job と、その Step を Bean で定義する `@Configuration` であり、Job を持つ機能モジュールの `batch` に package-private で置く。
Step には `transactionManager` と `transactionAttribute` を渡さず、業務の書き込みを ItemWriter と Tasklet が呼ぶ CommandHandler に任せる。
Job の規則は [バッチ](../../batch/index.md) に、役割の決定理由は [ADR-115](../../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 定義

業務のバッチでは、どの Step をどの順に流し、何件ずつ処理し、どの失敗を飛ばすかを、どこか 1 か所で決める必要がある。
**Job の設定**（`<Job>JobConfig`）は、1 つの Job について、Job、Step、partition、skip と retry の方針を Bean で組み立てるクラスである。

Job の設定は業務処理の置き場所ではない。
業務規則と保存は、[ItemWriter](item-writer.md) と [Tasklet](tasklet.md) が呼ぶ [CommandHandler](command-handler.md) が担う。
読む行の選び方は [ItemReader](item-reader.md) が担う。

## 置き場所と命名

- Job を持つ機能モジュールの `com.example.demo.<module>.batch` に置く（`com.example.demo.ordering.batch`）。
- クラスの名前は、Job の名前の JobName に `JobConfig` を付ける（`CloseMonthJobConfig`）。
- Job の名前は `<module>.<JobName>` にする（`ordering.CloseMonth`）。
  起動のときに `spring.batch.job.name` に渡す名前で、設計書の識別子でもある。
- Step の名前は、Job の名前に `.` と Step の役割を付ける（`ordering.CloseMonth.close`）。
- 1 つの Job に 1 つの `<Job>JobConfig` を作る。

## 必須の記述

- `@Configuration(proxyBeanMethods = false)` を付けた package-private の `class` にする。
- Job と Step を `@Bean` のメソッドで定義する。
  Spring Boot が Job と Step に `ObservationRegistry` を渡し、`*_tx_id` に入るスパンができるのは Bean だけである。
- Step に `transactionManager` と `transactionAttribute` を渡さない。
- 期間で区切る Job は、`JobParametersValidator` で `period` を必須にし、追いつきの Runner が使う期間の規則（期間の長さと最初の期間）を同じクラスで定義する（[定時起動と追いつき](../../batch/scheduling-and-catch-up.md)）。
- テナントの業務の表を書く Step は、テナントごとの partition にし、partition の ExecutionContext にテナントの `public_id` を置く。
- skip と retry は、[冪等性と再開](../../batch/idempotency-and-restart.md#skip-と-retry) の方針で `faultTolerant()`、`retryPolicy`、`skipPolicy` に書く。
- `JobParametersIncrementer` を付けない。
- クラスと `@Bean` のメソッドに Javadoc を書く。

## 依存してよい型、してはいけない型

- **依存してよい型**：Spring Batch の Job、Step、builder、`JobRepository`、同じ `batch` の ItemReader、ItemWriter、Tasklet、`tenancy` の `<Feature>Queries`（partition の一覧を作るとき）。
- **依存してはいけない型**：CommandHandler、Repository、`TableWriter`、`DSLContext`、`PlatformTransactionManager`、Presentation と Infrastructure の型。

## 最小の例

次は、テナントごとの partition で月次の締めを流す Job の設定の抜粋である。
まだこのリポジトリに Job はないので、名前は例である。

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

- Step に `transactionManager` か `transactionAttribute` を渡し、`handle` を chunk のトランザクションに加える。
  Step の状態の保存がモジュールのロールで流れ、メタデータの表の権限で失敗する。
- Job や Step を `@Bean` にせず、メソッドの中で組み立てて起動する。
  observation が渡らず、`*_tx_id` の登録が trace がないことで失敗する。
- Job の設定に業務の条件分岐を書く。
- `JobParametersIncrementer` を付ける。
  失敗した期間を飛ばして次の期間を流す。

## 作成時のチェックリスト

- [ ] `<module>.batch` に `<JobName>JobConfig` として置く。［自分で点検］
- [ ] Job の名前を `<module>.<JobName>` にする。［自分で点検］
- [ ] Job と Step を `@Bean` で定義する。［自分で点検］
- [ ] Step に `transactionManager` と `transactionAttribute` を渡さない。［自分で点検］
- [ ] `JobParametersValidator` で必須の引数を検査する。［自分で点検］
- [ ] テナントの業務の表を書く Step を、テナントごとの partition にする。［自分で点検］
- [ ] `JobParametersIncrementer` を付けない。［自分で点検］
- [ ] Job を起動する結合テストがある。［自分で点検］
