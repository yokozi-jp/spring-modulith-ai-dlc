---
type: Convention
title: バッチの冪等性と再開
description: 業務のバッチの冪等性の意味、JobInstance と再開、DB の reader の process indicator、ItemWriter と Tasklet の冪等な書き方、skip と retry、二重の起動の止め方、入力の検査の濃淡、DB への接続を並列にしない規則を定める規約。Job の reader と writer を作るとき、再開や二重の起動で結果が変わらないかを確かめるときに読む。
tags: [convention, batch, spring-batch, idempotency, future-arch-guidelines]
---

# バッチの冪等性と再開

同じ JobInstance を何度起動しても、DB の状態が 1 回だけ流したときと同じになるように作る。
DB から読む reader は、業務の行の状態の列で未処理だけを選ぶ process indicator にし、読んだ位置を保存しない。
再開と skip の再試行で同じ行が ItemWriter と Tasklet にもう一度渡るので、呼ぶ CommandHandler を冪等にする。
二重の起動は JobRepository が止め、書き込みの正しさは業務のキーの一意制約と状態の遷移の条件で守る。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 冪等性の意味

冪等とは、同じ JobInstance を何度起動しても、DB などに永続化した結果が同じになることを指す。
Job のふるまいは毎回同じでなくてよい。
2 回目に何もせずに終わる Job も冪等である。

## JobInstance と再開

- JobInstance は、Job の名前と、識別に使う JobParameters の組で決まる。
- 失敗した JobInstance を同じ識別の引数で起動すると、Spring Batch は同じ JobInstance の新しい JobExecution として再開する。
  COMPLETED の Step は流さず、失敗した Step を、最後に保存した chunk の境界から流す。
- Step は DB のトランザクションを持たず（[ジョブの入口と起動](job-entry.md#トランザクションとロール)）、業務の書き込みは 1 件ごとに commit する。
  再開すると、失敗した chunk のうち commit 済みの行が、もう一度 ItemWriter と Tasklet に渡る。

## DB から読む reader

- DB から読む ItemReader は process indicator にする。
  業務の行の状態の列（締め済み、配信済みなど）で未処理の行だけを選び、読んだ位置を ExecutionContext に保存しない。
  再開すると、未処理の行を先頭から読み直す。
- 未処理の行は、業務のキーの昇順で、前に読んだキーより後の行を決まった件数ずつ読む。
  skip した行は未処理のまま残るので、同じ JobExecution の中で読み直さないためである。
- 全件をメモリに載せない。
- 読み方は [クラスの役割：ItemReader](../backend/class-roles/item-reader.md) に従う。

## ファイルから読む reader

- ファイルから読む ItemReader は、Spring Batch の reader の既定のとおり、読んだ位置を ExecutionContext に保存してよい。
- 再開しても、位置を保存した chunk の境界の後の行が、もう一度 ItemWriter に渡ることがある。
  ItemWriter が呼ぶ CommandHandler を、業務のキーで冪等にする。
- ファイルの取り込みの規則は [連携ファイルの検証と取り込み範囲](../integration/interface-receiving-validation.md) と [連携データの整合性と冪等性](../integration/interface-data-consistency.md) に従う。

## 冪等な書き方

ItemWriter と Tasklet が呼ぶ CommandHandler は、次のどちらかで冪等にする。

- **状態で未処理を判定する**：集約の状態がすでに処理済みなら、何もせずに Result を返す。
  並行の 2 本目は、状態の遷移を WHERE に書いた UPDATE の件数で止める。
- **業務のキーで 1 行にする**：結果の行を業務のキー（テナントと期間など）の一意制約で 1 行にする。
  同じキーの行があれば、その行を返すか、同じ値で更新する（MERGE に当たる書き方）。
  一意制約で負けた側は、`ConflictException` で止まる。

冪等なコンシューマーの書き方は [順序保証と冪等性](../integration/async-ordering-and-idempotency.md#冪等なコンシューマー) と同じである。

## skip と retry

- 1 件の失敗は、Spring Batch 6 の `ChunkOrientedStepBuilder` の `faultTolerant()`、`retryPolicy`、`skipPolicy` で扱う。
- `ConflictException`（楽観的ロックの競合と一意制約）は retry する。
  `handle` が集約を読み直すので、競合が解ければ成功する。
  retry の上限を超えたら skip する。
- 業務上の失敗の例外（[業務上の失敗の例外](../backend/class-roles/business-exception.md)）は skip する。
  skip の上限は Job ごとに決め、上限を超えたら Step を失敗させる。
- DB に接続できないなど、ほかの例外は skip も retry もせず、Step を失敗させる。
  次の起動が再開で続きを流す。
- skip した行は、`SkipListener` で WARN のログに記録し、その event 名を [運用者が対応するログイベントの管理](../observability/log-operational-events.md) に足す。
  skip した行は未処理のまま残り、次の JobInstance か再開で読み直される。

## 二重の起動

- 同じ JobInstance の 2 本目は、Spring Batch が `JobExecutionAlreadyRunningException` で止める。
- COMPLETED の JobInstance をもう一度起動すると、Spring Batch が `JobInstanceAlreadyCompleteException` で止める。
- 業務のバッチには ShedLock を使わない。
- JobRepository の検査があっても、書き込みは上の冪等な書き方で守る。
  同じ行を 2 本の Job が同時に書いても、DB が 1 本だけを通す。
- 連携のジョブの多重起動と前回の異常終了の扱いは、[ジョブとファイルの順序制御](../integration/interface-job-ordering.md) にも従う。

## 入力の検査

- JobParameters は、`JobParametersValidator` で起動の時点に検査し、不正なら Job を流さずに失敗させる。
  検査は単体テストで確かめる。
- デプロイのときに決まる環境変数は、アプリで検査しない。
- 入力ファイルの検査は [連携ファイルの検証と取り込み範囲](../integration/interface-receiving-validation.md) に従う。
- 自分のシステムが作った処理対象のデータ（業務の表）は正しい前提で読み、Job の起動の時点で不整合を探さない。
  不整合が入ったときは、Job ではなくデータパッチで直す。
- Job が書いた結果を、同じ Job の中でアプリが検証しない。
  テストで確かめる（[バッチのテスト観点](testing.md)）。

## DB への接続を並列にしない

ItemWriter や Tasklet の中で、スレッドを作って DB に接続しない。
トランザクションがスレッドごとに分かれ、Job の成否と一致しなくなり、再開の位置が決まらないためである。
並列にするときは、Spring Batch の partition で Step を分ける（[ジョブの入口と起動](job-entry.md#スパンとテナント)）。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
