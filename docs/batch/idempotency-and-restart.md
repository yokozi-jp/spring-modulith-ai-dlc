---
type: Convention
title: バッチの冪等性と再開
description: 業務のバッチの冪等性の意味、JobInstance と再開、DB とファイルから読む reader の位置の扱い、ItemWriter と Tasklet が呼ぶ CommandHandler の冪等な書き方、retry と失敗の扱いを定め、再開しても結果が 1 回だけ流したときと同じになる作り方を示す規約で、Job の reader と writer を作るときや再開で結果が変わらないかを確かめるときに読む。
tags: [convention, batch, spring-batch, idempotency, future-arch-guidelines]
---

# バッチの冪等性と再開

同じ JobInstance を何度起動しても、DB の状態が 1 回だけ流したときと同じになるように作る。
skip を使わず、retry で解けない失敗は Step を失敗させて、次の起動の再開で続ける。
再開と retry で同じ行がもう一度渡るので、ItemWriter と Tasklet が呼ぶ CommandHandler を冪等にする。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 冪等性の意味

冪等とは、同じ JobInstance を何度起動しても、DB などに永続化した結果が同じになることを指す。
Job のふるまいは毎回同じでなくてよい。
2 回目に何もせずに終わる Job も冪等である。

## JobInstance と再開

- JobInstance は、Job の名前と、識別に使う JobParameters の組で決まる。
- 失敗した JobInstance を同じ識別の引数で起動すると、Spring Batch は同じ JobInstance の新しい JobExecution として再開する。
  COMPLETED の Step は流さず、失敗した Step を、最後に保存した chunk の境界から流す。
- 業務の書き込みは 1 件ごとに commit する（[トランザクション、ロール、テナント](transaction-and-tenant.md#トランザクションとロール)）。
  再開すると、失敗した chunk のうち commit 済みの行が、もう一度 ItemWriter と Tasklet に渡る。

## DB から読む reader

DB から読む ItemReader は、業務の行の状態の列で未処理の行だけを選ぶ process indicator にし、再開すると未処理の行を読み直す。
形と読み方は [クラスの役割：ItemReader](../backend/class-roles/item-reader.md) に従う。

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

## retry と失敗

- `ConflictException`（楽観的ロックの競合、一意制約、ロック待ちの上限）は、Spring Batch 6 の `ChunkOrientedStepBuilder` の `faultTolerant()` と `retryPolicy` で retry する。
  `handle` が集約を読み直すので、競合が解ければ成功する。
- retry の上限を超えた `ConflictException` と、ほかのすべての例外は、Step を失敗させる。
  業務上の失敗の例外（[業務上の失敗の例外](../backend/class-roles/business-exception.md)）も Step を失敗させる。
  Job は FAILED で終わり、次の起動で再開する。
- skip を使わない。
  Step の builder の `skipPolicy`、`skip`、`skipLimit` を呼ばない。
- ロックを取れない行を `SELECT ... FOR UPDATE SKIP LOCKED` で飛ばさない。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
