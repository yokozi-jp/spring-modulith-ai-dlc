---
type: Runbook
title: 止まった期間を直す
description: 業務のバッチの追いつきの Runner が流せずに止まった期間（JobExecution のない JobInstance、プロセスが落ちるか Job の実行中の例外で STARTED のまま残った JobExecution、同じ行で失敗し続ける FAILED、UNKNOWN）を見分けて直す手順と、ABANDONED の期間の扱いを示す。Job の起動が終了コード 1 か 5 で続けて終わるとき、期間を Job で流さないと決めたときに読む。
tags: [runbook, batch, spring-batch]
---

# 止まった期間を直す

止まった期間は、次の起動の再開でも COMPLETED にならず、追いつきの Runner がそれより後の期間を流さない期間である。
期間の最後の JobExecution の状態で見分け、状態ごとの手順で直してから、次の起動で再開させる。
最後の JobExecution が ABANDONED の期間は止まった期間に当たらず、Runner は次の期間へ進む（[追いつきの Runner](scheduling-and-catch-up.md#追いつきの-runner)）。

## 前提

- 期間と Runner の順は [定時起動と追いつき](scheduling-and-catch-up.md#追いつきの-runner) に従う。
- 手順が呼ぶ `JobOperator` の `recover` と `abandon`、`JobRepository` の `deleteJobInstance` を運用者が呼ぶ手段は、Job を足す [#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667) で作る。
- データパッチは [PostgreSQLの共通カラム](../database/postgresql-common-columns.md#データパッチ) に従う。

## 止まった期間の見分け方

期間の最後の JobExecution を JobRepository の表で読み、次の表で手順を選ぶ。

| 最後の JobExecution                                       | 次の起動で起きること                                 | 手順                                                                                                      |
| --------------------------------------------------------- | ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| ない                                                      | `IllegalStateException` で終了コード 1               | [JobExecution のない JobInstance を消す](#jobexecution-のない-jobinstance-を消す)                         |
| STARTED（プロセスが落ちたか、Job の実行中の例外で残った） | `JobExecutionAlreadyRunningException` で終了コード 1 | [STARTED のまま残った JobExecution を FAILED にする](#started-のまま残った-jobexecution-を-failed-にする) |
| FAILED（同じ行で毎回失敗する）                            | 再開して同じ行で失敗し、終了コード 5                 | [失敗し続ける行を直す](#失敗し続ける行を直す)                                                             |
| UNKNOWN                                                   | `JobRestartException` で終了コード 1                 | [UNKNOWN の期間を揃えて ABANDONED にする](#unknown-の期間を揃えて-abandoned-にする)                       |

## JobExecution のない JobInstance を消す

Spring Batch 6.0.6 の `TaskExecutorJobLauncher` は、JobInstance を作ってから引数を検査し、別のトランザクションで JobExecution を作る。
その間で落ちた起動と、引数の検査に失敗した起動は、JobExecution のない JobInstance を残す。

1. 同じ Job を動かしているプロセスがないことを確かめる。
   ほかのプロセスが JobInstance を作った直後で、JobExecution をまだ作っていないことがある。
2. `JobRepository` の `deleteJobInstance` で、その JobInstance を消す。
3. 次の起動で、Runner が同じ期間の JobInstance を作り直して流すことを確かめる。

## STARTED のまま残った JobExecution を FAILED にする

1. 同じ Job を動かしているプロセスがないことを確かめる。
2. `JobOperator` の `recover` で、JobExecution と動いている状態の StepExecution を FAILED にする。
   `recover` は、COMPLETED、ABANDONED、UNKNOWN 以外の状態の JobExecution を FAILED にする。
3. 次の起動で、Spring Batch が失敗した Step から再開することを確かめる。

## 失敗し続ける行を直す

1. Job の ERROR のログで、失敗した行と例外を確かめる。
2. 失敗した行をデータパッチで直す。
3. 次の起動で、同じ JobInstance が再開して COMPLETED になることを確かめる。

## UNKNOWN の期間を揃えて ABANDONED にする

`recover` は UNKNOWN の JobExecution を直せない。

1. 期間の業務の行を確かめ、結果をデータパッチで揃える。
2. `JobOperator` の `abandon` で、JobExecution を ABANDONED にする。
3. 次の起動で、Runner が次の期間へ進むことを確かめる。

## 期間を Job で流さないと決めたとき

1. 最後の JobExecution が STARTED なら、[STARTED のまま残った JobExecution を FAILED にする](#started-のまま残った-jobexecution-を-failed-にする) の手順 1 と 2 で FAILED にする。
   `abandon` は、COMPLETED、STARTING、STARTED の JobExecution に使えない。
2. `JobOperator` の `abandon` で ABANDONED にする。
3. 期間の結果が揃っていなければ、データパッチで揃える。
   ABANDONED の期間は Job で流し直せない。
