---
type: Convention
title: バッチの定時起動と追いつき
description: 業務のバッチの起動時刻の決め方、期間で区切る Job の JobInstance と追いつきの Runner、0 件の扱い、前回が失敗したときの次回、未起動と遅延の検知のメトリクスと閾値、システムタイムチャートを定める規約。定時に起動する Job を作るとき、起動時刻や期間の区切りを決めるとき、未起動や遅延の監視を作るときに読む。
tags: [convention, batch, spring-batch, scheduling, future-arch-guidelines]
---

# バッチの定時起動と追いつき

期間で区切る Job は、期間の初日を識別の引数にして 1 つの期間を 1 つの JobInstance にし、月次の Job も毎日起動する。
起動のたびに、追いつきの Runner が COMPLETED でない期間を古い順に流し、処理する期間がなければ何もせずに終える。
起動時刻は業務時間帯にし、門限とリカバリの余裕から決め、ほかの Job と重ねない。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 起動時刻

- 起動時刻は、できれば業務時間帯にする。
  失敗したときに、運用者がその日のうちに直しやすいためである。
- 起動時刻は、Job の処理時間、門限、リカバリの余裕から決める。
  たとえば、10 分で終わる Job を 17 時までに終えるなら、16 時 45 分でなく、遅くとも 16 時 30 分、できれば 16 時に起動する。
- 門限に間に合わないときは、大部分を早い時刻に処理する Job と、その後にたまった分を処理する Job に分けられないかを業務と決める。
- ほかの Job と起動時刻を重ねない。
  数分ずつずらすだけでもよい。
- Job の間に依存ができたら、システムタイムチャート（各 Job の起動時刻、処理時間、依存、門限を並べたガントチャート）を作る。
  図の書き方は [設計書の図](../writing/design-doc-diagrams.md) に従う。

## 期間と JobInstance

- 期間で区切る Job（月次の締め、日次の集計など）は、期間の初日を、識別に使う JobParameter `period`（`LocalDate`）にする。
  1 つの期間を 1 つの JobInstance にする。
- 期間の初日と境界の時刻は、Java で業務タイムゾーンから求め、SQL には引数で渡す。
  SQL の `now()` や `date_trunc` で境界を作らない（[暦日のAPIとSQL](../datetime/timezone-conventions.md#暦日のapiとsql)）。
- 期間に入る行の検索は、半開区間で書く（[PostgreSQLのデータ型](../database/postgresql-data-types.md#値の種類ごとの型)）。
- 業務日付管理テーブルを持つかは、[業務日付](../database/postgresql-temporal-data.md#業務日付) の条件で決める。
  持つモジュールでは、期間を業務日付から求める。
- 自前の「最後に処理した期間」の列を作らない。
  どの期間が COMPLETED かは JobRepository から読む。

## 追いつきの Runner

期間で区切る Job は、Spring Boot の `JobLauncherApplicationRunner` を継承して `execute` を上書きした、追いつきの Runner で起動する。
Runner は、起動のたびに次の順で期間を流す。

1. Job の最後の JobInstance を `JobRepository` から読む。
2. 最後の JobInstance が COMPLETED ならその次の期間を、COMPLETED でなければその期間を、最初の期間にする。
   JobInstance がなければ、Job の設定が決める最初の期間にする。
3. 最初の期間から、起動した時刻（`Instant.now(clock)`）より前に終わった最後の期間までを、古い順に 1 つずつ流す。
4. 流した期間が COMPLETED にならなければ、そこで止める。
   残りの期間は次の起動で流す。

- 期間を求める処理は、最後の期間、最初の期間、起動した時刻、業務タイムゾーンから期間の一覧を返す純粋な関数にする。
- 期間を引数で渡して流す起動を作らない。
- `JobParametersIncrementer` を使わない。
  incrementer を持つ Job は、渡した引数が捨てられ、前の JobExecution の引数から次の引数が作られるので、失敗した期間を飛ばす。
- 月次のように周期の長い Job も毎日起動し、流す期間がなければ何もせずに終える。
  起動の設定と Job が動くことを、毎日確かめられる。

## 0 件の扱い

- 流す期間がないときは、Job を流さずに INFO で記録し、終了コード 0 で終える。
- 期間に処理する行がないときも、その期間の JobInstance を COMPLETED にする。
  終了のログに 0 件を出す（[処理ごとに記録するログ](../observability/log-output-points.md#一括処理)）。
- 0 件がありえない Job では、0 件のときに Step を失敗させ、終了コードで知らせる。
- 定時の配信は、0 件でも空ファイルを配信する（[連携ファイルのレイアウト](../integration/interface-file-layout.md#空ファイル)）。

## 前回が失敗したときの次回

- 前回の起動で失敗した期間は、次の起動で追いつきの Runner が同じ引数で流し、Spring Batch が失敗した Step と chunk から再開する。
  手での再実行を前提にしない。
- 失敗した期間が COMPLETED になるまで、それより後の期間を流さない。
- 異常終了で STARTED のまま残った JobExecution があると、次の起動は `JobExecutionAlreadyRunningException` で失敗する。
  運用者が、その JobExecution が動いていないことを確かめてから、Spring Batch の `JobOperator` の `recover` で FAILED にする。

## 未起動と遅延の検知

- 期間で区切る Job ごとに、ゲージ `batch.job.period.lag` を出す。
  値は、起動した時刻より前に終わった期間のうち、COMPLETED でない期間の数である。
  属性は `job.name`（Job の名前）だけにする。
- ゲージは Web のアプリが JobRepository の表から求める。
  Job のプロセスは短い間しか動かないためである。
- 閾値は、Job の門限の時刻に 1 以上なら警報にする。
  起動しなかったとき、遅れているとき、失敗したときのどれも、この 1 つで検知する。
- 警報をどこから出すかは、起動元と一緒に決める。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
