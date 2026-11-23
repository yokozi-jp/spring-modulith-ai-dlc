---
type: Convention
title: バッチの定時起動と追いつき
description: 業務のバッチの起動時刻の決め方、期間で区切る Job の JobInstance と期間の規則、期間の後に届く行の扱い、追いつきの Runner が期間を流す順、0 件の扱い、システムタイムチャートを定める規約で、定時に起動する Job を作るときや起動時刻と期間の区切りを決めるときに読む。
tags: [convention, batch, spring-batch, scheduling, future-arch-guidelines]
---

# バッチの定時起動と追いつき

期間で区切る Job は、期間の初日を識別の引数にして 1 つの期間を 1 つの JobInstance にし、月次の Job も毎日起動する。
起動のたびに、追いつきの Runner が済んでいない期間を古い順に流し、処理する期間がなければ何もせずに終える。
期間は、終わってから決めた時間を待って流し、締まった後にその期間の行が増えない列で行を選ぶ。
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
  どの期間が済んだかは JobRepository から読む。

## 期間の規則

- 期間で区切る Job は、`shared.batch` の `JobPeriodRule` を Bean で定義する。
  定義のしかたは [クラスの役割：Job の設定](../backend/class-roles/job-config.md) に従う。
- `JobPeriodRule` は、Job の名前、最初の期間の初日、期間の長さ（`java.time.Period`）、締めるまで待つ時間（`Duration`）を持つ。
- 期間が締まる時刻は、期間の終わりの時刻に、締めるまで待つ時間を足した時刻である。
  期間の終わりの時刻は、次の期間の初日の 0 時を業務タイムゾーンで絶対時刻にしたものである。
- `JobPeriodRule` がない Job は、期間で区切らない Job として扱う。
  識別の引数は [ジョブの入口と起動](job-entry.md#識別の引数) に従う。

## 期間の後に届く行

- Job が期間で行を選ぶ列は、期間が締まった後に、その期間の値を持つ行が増えない列にする。
- 行を書いた時刻（`handle` が `Instant.now(clock)` で取った時刻）の列で選ぶときは、締めるまで待つ時間を、業務の書き込みの `handle` の最も長いトランザクションと、インスタンスの間の時計のずれの上限を足した時間より長くする。
  行を書くインスタンスと Runner を動かすインスタンスは別で、時計が同じ時刻を指すとは限らない。
- 利用者や連携相手が渡す日時（過去の日付を持つ受信のファイルなど）で選ぶときは、次のどちらかを Job の設計書で決める。
  - 締まった期間の値を持つ行の書き込みを、CommandHandler が業務上の失敗の例外で拒否する。
  - 締まった期間の値を持つ行を、受け取った日の期間に入れる列を別に持ち、Job はその列で選ぶ。
- 締めるまで待つ時間の値は、Job の設計書に書く。

## 追いつきの Runner

追いつきの Runner は、起動のたびに次の順で期間を流す。
Runner の起動と終了は [ジョブの入口と起動](job-entry.md#追いつきの-runner-の起動) に従う。

1. Job の最後の JobInstance を `JobRepository` の `getLastJobInstance` で読み、その最後の JobExecution を `getLastJobExecution` で読む。
2. 最初の期間を次のとおり決める。
   - JobInstance がなければ、`JobPeriodRule` の最初の期間にする。
   - 最後の JobExecution が COMPLETED か ABANDONED なら、その JobInstance の次の期間にする。
     ABANDONED は、運用者がその期間を Job で流さないと決めた印である。
   - 最後の JobExecution がない（`getLastJobExecution` が `null` を返す）か、ほかの状態なら、その JobInstance の期間にする。
     JobExecution のない JobInstance を流すと Spring Batch が `IllegalStateException` を投げ、Runner は終了コード 1 で終える（[止まった期間を直す](runbook-stuck-period.md)）。
3. 最初の期間から、締まる時刻が起動した時刻（`Instant.now(clock)`）以前の最後の期間までを、古い順に 1 つずつ `super.execute` で流す。
4. 流した期間の最後の JobExecution を JobRepository から読み、COMPLETED でなければそこで止める。
   残りの期間は次の起動で流す。

- 期間の一覧を求める処理は、`JobPeriodRule`、最初の期間、起動した時刻、業務タイムゾーンから期間の一覧を返す純粋な関数にする。
- 期間を引数で渡して流す起動を作らない。
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
- 再開できない状態で止まった期間は、[止まった期間を直す](runbook-stuck-period.md) の手順で運用者が直す。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
