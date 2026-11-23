---
type: Convention
title: バッチとオンラインの排他
description: 業務のバッチとオンラインの処理が同じ時間帯に同じ表を書くときの排他の要否の分類、ロックを使わない条件、集約を更新するときの楽観的ロックと retry、採らない排他の形を定める規約で、オンラインの時間帯に動く Job を作るときや Job とオンラインが同じ表を書くかを確かめるときに読む。
tags: [convention, batch, concurrency, future-arch-guidelines]
---

# バッチとオンラインの排他

排他の要否は、Job とオンラインが、同じ時間帯に、同じ表の同じ行を書くかで決める。
Job が読む行がオンラインでもう変わらない状態にあり、Job が自分のモジュールの表とイベントだけを書くなら、ロックも `REPEATABLE READ` も使わない。
集約を更新するなら、読んだ版と行の版を比べ、競合した 1 件は retry で読み直し、それでも競合すれば Step を失敗させる。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## 要否の分類

画面を閉じる時間がない業務では、定期の保守の時間を除くと、どの Job もオンラインの時間帯に動く。
Job ごとに、次の表で排他の要否を決める。

| Job とオンラインの関係               | 排他の要否                                     |
| ------------------------------------ | ---------------------------------------------- |
| 同じ時間帯に動かない                 | 要らない                                       |
| 同じ時間帯に動くが、同じ表を書かない | 要らない                                       |
| 同じ時間帯に、同じ表の同じ行を書く   | 要る。下の「集約を更新する Job」の書き方にする |

同じ表の別の列を書くときも、行の版（`lock_no`）は行ごとに 1 つなので、同じ行を書く場合として扱う。
Job とオンラインで書く列がはっきり分かれるなら、表を分けて、同じ行を書かない形にできないかを先に検討する。

排他が要るときは、まず起動時刻をオンラインがその表を書かない時間帯に動かせないかを、業務と決める。
動かせないときに、下の書き方で排他する。

## ロックを使わない条件

次の両方が成り立つ Job は、行のロックも `REPEATABLE READ` も使わない。

- Job が読む行が、オンラインでもう変わらない状態にある（締めの対象の期間が終わった注文、確定した明細など）。
- Job が書くのが、自分のモジュールの表と、自分が発行するイベントだけである。

Job が読む行の状態は、ItemReader の process indicator の条件（[冪等性と再開](idempotency-and-restart.md#db-から読む-reader)）で決める。

## 集約を更新する Job

- オンラインも変える集約を Job が更新するときは、CommandHandler が集約を読み、Repository の `update` で、読んだ版と行の版を比べる（[PostgreSQLの排他制御](../database/postgresql-concurrency-control.md#楽観的ロック)）。
- 競合した 1 件と、ロック待ちの上限を超えた 1 件は、`ConflictException` を Spring Batch の retry で受け、`handle` に集約を読み直させる（[PostgreSQLの排他制御](../database/postgresql-concurrency-control.md#ロック待ち)）。
  retry の上限を超えたら Step を失敗させ、次の起動の再開で続ける（[冪等性と再開](idempotency-and-restart.md#retry-と失敗)）。
- 競合が続いて期間が終わらないときは、起動時刻をオンラインがその表を書かない時間帯に動かせないかを、業務と決める。

## 採らない排他の形

- オンラインが `SELECT ... FOR UPDATE NOWAIT` で悲観的ロックを取り、Job が常に後勝ちで上書きする形は採らない。
  オンラインは読んだ版と行の版を比べる楽観的ロックで、HTTP の要求と応答をまたいで行のロックを保てない（[PostgreSQLの排他制御](../database/postgresql-concurrency-control.md#悲観的ロック)）。
- Job の起動の時点に表のロックを取る形は採らない。
  オンラインの書き込みが Job の終わりまで待たされるためである。

## 出典

- フューチャー株式会社「バッチ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
