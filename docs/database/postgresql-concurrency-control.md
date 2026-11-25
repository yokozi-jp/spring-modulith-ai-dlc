---
type: Convention
title: PostgreSQLの排他制御
description: トランザクション分離レベル、楽観的ロック、悲観的ロック、DBの行ロックの使い分けと、業務テーブルのINSERTとUPDATEをTableWriterで書く規則、ロック待ちとデッドロックの扱いを定める規約。業務テーブルのINSERTかUPDATEを書くとき、ロックの方式を選ぶとき、ロック待ちの失敗とデッドロックを扱うとき。
tags: [convention, database, postgresql, concurrency, locking, future-arch-guidelines]
---

# PostgreSQLの排他制御

分離レベルはPostgreSQLの既定のREAD COMMITTEDのまま使う。
同じ行を同時に更新しうる処理には楽観的ロックを使い、UPDATEの条件で`lock_no`を比較して更新件数で判定する。
業務テーブルのINSERTとUPDATEは、sharedモジュールの`TableWriter`だけで書く。
画面を開いた時点で行をロックする悲観的ロックは避ける。

## 分離レベル

分離レベルはREAD COMMITTEDにし、`@Transactional`の`isolation`を指定しない。
業務処理に必要な整合性は、UPDATEと`SELECT ... FOR UPDATE`が取る行ロックで守る。
PostgreSQLではREPEATABLE READ以上にすると、直列化の失敗によるエラーが積極的に起きるためである。

次のどちらかに当てはまる場合に限り、REPEATABLE READを使ってよい。

- WHERE句のサブクエリが、更新対象のテーブルを自己参照する。
- 複雑なレポートの作成など、トランザクションの中で数値の計算の整合性を保つ必要がある。

## ロック方式の選び方

複数の利用者や処理が同じテーブルの行を同時に変更しうる場合は、ロックを取り、他の誰かの変更を誤って上書きしないようにする。

- **楽観的ロック**：変更前にロックを取らず、更新時にバージョンを比較して競合を検知する。競合したら利用者に入力し直してもらう。
- **悲観的ロック**：変更前にロックを取り、他の処理が対象の行を変更できないようにする。
- **DBの行ロック**：UPDATE文の条件と更新件数で、業務上正しく更新できたかを判定する。

楽観的ロックを優先して使う。
悲観的ロックの画面設計は、入力中のデータを破棄することが業務上どうしても許容できない場合を除き避ける。
DBの行ロックは、在庫の引き当てのように性能が重要になる処理でだけ使う。
複数のインスタンスで定期のジョブを1つに絞るときは、ShedLockのロックを使う（後述の「定期のジョブの排他」）。

## 楽観的ロック

ロック番号には[共通カラム](postgresql-common-columns.md)の`lock_no`を使い、最終更新日時で代用しない。
業務テーブルのINSERTとUPDATEは、sharedモジュールの`TableWriter`だけが組み立てて実行する（[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
版の条件、`lock_no`の設定、共通カラム、更新件数の判定、ロック待ちと一意制約の違反の変換、親の外部キーの条件は`TableWriter`が持ち、Repositoryは業務の列の値だけを書く。
Repositoryでの書き方は[jOOQのRepository](../backend/class-roles/jooq-repository.md)に従う。

Repositoryは集約の形を、クラスの定数として一度だけ宣言する。
`AggregateTable`はルートのテーブル、ルートを一つに決める一意の列、内部の主キー、子を持ち、`ChildTable`は子のテーブル、親の外部キーの列、親の中で行を同定する自然キーを持つ。
保存のたびに、ルートの列の値と、保存後にあるべき子の全行（`ChildTable.rows`が作る`ChildRows`）を渡す。`Condition`は渡さない。

- **`insert`**：ルートを`INSERT ... RETURNING 内部の主キー`で書き、続けて子を複数行のINSERTで書く。`lock_no = 1`にする。
- **`insertIfAbsent`**：`insert`と同じく書くが、指定した一意索引の列の衝突を例外にせず、`INSERT ... ON CONFLICT (列) DO NOTHING`で何も書かずに`false`を返す。入れなかったときは子を書かない。
  同じ値の行を書いて未コミットのトランザクションがあれば、その終わりまで待つ。同じイベントの2回目を処理済みとして正常に終えるときに使う（[ADR-129](../adr/ADR-129-check-all-listeners-for-idempotency-without-a-uniform-processed-message-table.md)）。
- **`update`**：以前に読んだ集約ルート（`Versioned`）を受け取り、その`lockNo()`を期待する版にする。
  更新件数が1件なら成功する。
  0件なら一意の列で行の有無を確かめ、行がなければ`shared.failure`の`NotFoundException`を、行があれば「他の人が更新した」として`shared.concurrency`の`ConflictException`（種類は`version`）を投げる。
  2件以上は`IllegalStateException`にする。
  ルートの行ロックを取った後に、子を差分で書く（後述の「親子のテーブルの保存の順序」）。

`update`は、期待する版が6の行に対して次のSQLを実行する。

```sql
UPDATE t_order SET order_status_typ = 'PAID', updated_at = ..., updated_by = ..., updated_pgm_cd = ..., updated_tx_id = ..., lock_no = 7
WHERE public_id = ? AND lock_no = 6
RETURNING order_id;
```

どのUPDATEも`lock_no`を1進める。
版を比べる入口しかないため、版の数値を渡す引数も、版を比べない入口も`TableWriter`にはない。

### 対象と対象外の分け方

`lock_no`は全業務テーブルにあるため、列の有無では対象を分けない。
版を比べるかは、期待する版を持っているかで決まる。
機械の検査の列は、書き間違いを検出する検査である。

| 書き込みの種類                                                                              | 版を比べるか                                   | 使うAPI                                                                                                             | 機械の検査                                                                                            |
| ------------------------------------------------------------------------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| 画面からの集約の更新（Commandが`VersionedCommand`）                                         | 比べる（画面の版と読んだ版、読んだ版と行の版） | Requestが作る`ExpectedLockNo`、CommandHandlerの`ensureLockNo`、Repositoryの`update`の`TableWriter.update`           | R1からR4、H3                                                                                          |
| Listenerやバッチからの集約の更新（画面の版を持たない）                                      | 比べる（読んだ版と行の版）                     | Repositoryの`update`の`TableWriter.update`                                                                          | H3                                                                                                    |
| 集約の子の行の更新                                                                          | 比べない（ルートの行ロックと版で守る）         | `update`が差分で書く（子の`lock_no`を1進める）                                                                      | 親の外部キーは`TableWriter`が付ける                                                                   |
| 集約の子の行の削除、追加                                                                    | 比べない / 該当なし（追加は`lock_no = 1`）     | `update`が差分で書く。`insert`が複数行のINSERTで書く                                                                | 同上                                                                                                  |
| 集約ルートのINSERT                                                                          | 該当なし（`lock_no = 1`）                      | `TableWriter.insert`、重複を許すなら`TableWriter.insertIfAbsent`                                                    | H1（`insertInto`を`TableWriter`の外で呼べない）                                                       |
| 集約ルートの物理削除、DBの行ロック（在庫の引き当て）、一括更新、ワークテーブルの後始末      | 今は入口がない                                 | 必要になったとき、[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)を更新して入口を足す | H1                                                                                                    |
| Javaの外の書き込み（psqlのデータパッチ、Liquibaseのデータ変更、スキーマ変更に伴う値の設定） | 比べない                                       | [共通カラム](postgresql-common-columns.md)（データパッチでは`lock_no`を進めない）                                   | 検出しない（[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)のNegative） |
| フレームワークのテーブル（`modulith`と`liquibase`のスキーマ）                               | 対象外                                         | 対象外                                                                                                              | 対象外                                                                                                |

期待する版を持つ書き込みは、集約ルートを受け取るRepositoryの`update`に限る。
識別子と版を受け取るRepositoryのメソッドを作らない。

### 親子のテーブルの保存の順序

集約のように親子のテーブルを一緒に書くときは、`TableWriter`が先に親の行（集約のルート）を更新し、子の行は後で書く。
親の業務のカラムが変わらなくても、親の行を更新して`lock_no`を進める。

子だけが変わる保存でも親の`lock_no`が進むことは、次の2つで守る。

- 子を書く`ChildWriter`は`TableWriter`だけが呼ぶため、親の更新を通さずに子だけを書く入口がない。
- 子のテーブルを親の子として宣言し忘れる誤りと、`parentKey`の列の誤りは、`AggregateDeclarationInspectionTest`が集約の宣言をDBの外部キーと突き合わせて止める。

子の集合が増減する集約は、全置換ではなく差分で保存する。
全置換では、子の`created_*`が保存のたびに変わる。
`update`は、親の行ロックを取った後に、次の順で子を書く。

1. 保存済みの子の自然キーを読む。
2. 保存後にない子を、親の外部キーと自然キーの条件で削除する。
   保存後の子が空なら、すべての子を削除する。
3. 両方にある子を、1行ずつUPDATEして子の`lock_no`を1進める。
4. 保存済みにない子を、複数行のINSERTで書く。

保存後にあるべき子の全行を渡す置き換えのため、子の一部だけを渡すと残りを削除する。
宣言した子を渡さない呼び出しと、自然キーが`null`か重複する呼び出しは、SQLを実行する前に`IllegalArgumentException`にする。

### jOOQの楽観的ロックの機能

jOOQの`Settings`の`executeWithOptimisticLocking`と、コード生成の`recordVersionFields`を使わない。
これらは`UpdatableRecord.store()`で更新するときにしか働かず、このリポジトリはUPDATEを`TableWriter`のDSLで書くためである。

jOOQの機能の動作は[jOOQのマニュアル](https://www.jooq.org/doc/latest/manual/sql-execution/crud-with-updatablerecords/optimistic-locking/)を参照する。

## デッドロックの防止

`SELECT ... FOR UPDATE`で複数の行やテーブルをロックする場合は、ロックするテーブルの順序と、行の並び順（主キーの順など）を決め、すべての処理がそれに従う。
UPDATEも更新する行のロックを取るため、同じ順序に従う。
楽観的ロックでは、親の行を先に更新し、子の行は後で書く。
親の行ロックが同じ集約を書く`TableWriter`の処理を直列にするため、子の行の更新の順序は規則にしない。

## ロック待ち

アプリの接続には`lock_timeout`でロック待ちの上限を設定し、既定の無期限待ちのままにしない（[DB接続情報とロール分離](connections.md)、[ADR-055](../adr/ADR-055-set-db-time-limits-per-connection.md)）。
上限まで待ってもロックを取れなければ、文はSQLSTATE `55P03`で失敗する。
`TableWriter`のすべての文は、この失敗を、原因を付けずに種類`lock`（`ConflictException.Kind.LOCK`）の`ConflictException`にする（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
一意制約の違反（SQLSTATE `23505`）も、すべての文で原因を付けずに種類`unique`（`ConflictException.Kind.UNIQUE`）の`ConflictException`にする。
変換は例外の型ではなくSQLSTATEで決め、それ以外のSQLSTATE（`21000`など）は変換せずに投げる。理由は[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)にある。
原因の例外のメッセージにはSQLと重複したキーの値が入るため、原因を付けない（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
書き込みは、開いていて読み取り専用ではないトランザクションの中でだけ行う。そうでなければ、SQLを実行する前に`IllegalStateException`にする。

要件に合わせて、次の待ち方も使う。

- **`NOWAIT`**：DBの行ロックやバッチで`SELECT ... FOR UPDATE`に付け、ロックを取れなければ即座にエラーにする。
- **`SKIP LOCKED`**：バッチで`SELECT ... FOR UPDATE`に付け、ロックを取れない行を飛ばして残りの行をロックする。
- **`SET LOCAL lock_timeout`**：1つのトランザクションだけ待ち時間の上限を変える。

## 悲観的ロック

HTTPの要求と応答をまたいでDBのトランザクションを保てないため、一覧を表示した時点で`SELECT ... FOR UPDATE`によりロックを取る方式は実現できない。
画面を開いた時点でロックする要件が出たら、まず要件を調整する。
どうしても必要な場合は、ロック用のテーブルを作ってアプリケーションでロックを再現し、ロックを解放するタイミングを同時に設計する。

## DBの行ロック

数量の更新のように、前回の値を確認する必要がない処理は、DBの行ロックと更新件数で判定してよい。
`TableWriter`には、版を比べない更新の入口を今は持たない。理由と、足すときの手順は[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)にある。
その入口も`lock_no`を1進め、引き当ての前に読んだ画面の保存が競合になるようにする。
マスタの保守のように後勝ちの上書きを許容できない更新と、状態の遷移を伴う更新には使わない。

## 定期のジョブの排他

複数のインスタンスで定期のジョブを1つに絞るときは、ShedLockのjOOQのproviderで`modulith.shedlock`の行にロックを持つ（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
ロックの期限はDBの時刻で書き込んで比べ、ロックの操作は1文ずつの新しいトランザクションで終える。
接続を固定しないため、RDS ProxyやPgBouncerのトランザクションモードの後ろでも動き、`tableWritesGoThroughTableWriter`（H1）の例外も要らない。
アプリで使うロックの名前は次の表だけにし、新しい名前を足すときは同じ変更で表に足す。

| 名前                             | 使うクラス                                                                                | 用途                                                                           |
| -------------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `event-publication-resubmission` | `shared.eventresubmission`の`EventPublicationResubmitter`、`EventPublicationResubmitOnce` | 失敗したイベント出版の定期の再投入と手での1回の再投入を1つのインスタンスに絞る |

セッションのadvisory lock（`pg_advisory_lock`、`pg_try_advisory_lock`）は使わない。
ロックがセッションの状態であり、接続を多重化するプロキシの後ろでは保たれないためである（[設計の作り込みの程度と段階的な再構築](../principles/right-sizing.md)の「テンプレートの利用先で変わる前提」）。
本番のコードに使っていないことは`task lint-session-sql`が検査する。

ShedLockはロックの行を作ったことをメモリに覚えるため、`modulith.shedlock`の行を手で消さない。
消すと、同じインスタンスは再起動までロックを取れない。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 楽観的ロックの方式を、先にロックしてから比較する原典の方式から、UPDATEの条件と更新件数で判定し、業務テーブルのINSERTとUPDATEを集約の宣言から`TableWriter`が書く方式に変えている（[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
  jOOQの楽観的ロックの機能を使わない規則を追加している。
- ロック待ちの上限を、アプリの接続ごとに`lock_timeout`で設定する規則を追加している（[ADR-055](../adr/ADR-055-set-db-time-limits-per-connection.md)）。
