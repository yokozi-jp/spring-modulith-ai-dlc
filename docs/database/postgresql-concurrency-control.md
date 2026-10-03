---
type: Convention
title: PostgreSQLの排他制御
description: トランザクション分離レベル、楽観的ロック、悲観的ロック、DBの行ロックの使い分けと、ロック待ちとデッドロックの扱いを定める規約。複数の利用者や処理が同じ行を更新しうる機能を実装するとき、分離レベルを変えたくなったときに読む。
tags: [convention, database, postgresql, concurrency, locking, future-arch-guidelines]
---

# PostgreSQLの排他制御

分離レベルはPostgreSQLの既定のREAD COMMITTEDのまま使う。
同じ行を同時に更新しうる処理には楽観的ロックを使い、更新の直前に`SELECT ... FOR UPDATE`で`lock_no`を比較する。
画面を開いた時点で行をロックする悲観的ロックは避ける。

## 分離レベル

分離レベルはREAD COMMITTEDにし、`@Transactional`の`isolation`を指定しない。
業務処理に必要な整合性は、`SELECT ... FOR UPDATE`による行ロックで守る。
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

## 楽観的ロック

ロック番号には[共通カラム](postgresql-common-columns.md)の`lock_no`を使い、最終更新日時で代用しない。

1. 更新の直前に、対象の行を`SELECT ... FOR UPDATE`で取得して行ロックを取る。
2. 取得した`lock_no`と、画面などから受け取った`lock_no`を比較する。
3. 一致しなければロールバックし、競合を利用者へ返す。
4. 一致すれば、`lock_no`を1加算して更新する。

先に`SELECT ... FOR UPDATE`でロックしてから比較する順序を守り、UPDATEの条件にバージョンを指定して更新件数で判定する方式は使わない。
理由は次の3点である。

- 親の行（集約のルート）をロックすれば、同じトランザクションで更新する子テーブルの行も守られる。
- SELECTの段階で、「行がない」（404）と「他の人が更新した」（409）を区別できる。
- SELECTには`NOWAIT`を付けられるが、UPDATEには付けられない。

### jOOQの楽観的ロックの機能

jOOQの`Settings`の`executeWithOptimisticLocking`と、コード生成の`recordVersionFields`を使わない。
理由は次の4点である。

- jOOQはレコードに取得した値と比較するため、画面から受け取った`lock_no`と比較できない。
- `lock_no`をバージョンのカラムに指定すると、jOOQは`UPDATE ... WHERE lock_no = ?`と更新件数で判定する方式に切り替わる。この方式は上記のとおり使わない。
- jOOQが発行する`SELECT ... FOR UPDATE`に`NOWAIT`を付けられない。
- `UpdatableRecord.store()`で更新するときにしか働かない。

jOOQの機能の動作は[jOOQのマニュアル](https://www.jooq.org/doc/latest/manual/sql-execution/crud-with-updatablerecords/optimistic-locking/)を参照する。

## デッドロックの防止

`SELECT ... FOR UPDATE`で複数の行やテーブルをロックする場合は、ロックするテーブルの順序と、行の並び順（主キーの順など）を決め、すべての処理がそれに従う。

## ロック待ち

ロックの待ち方は要件に合わせて選び、既定の無期限待ちのままにしない。

- **`NOWAIT`**：ロックを取れなければ即座にエラーにする。画面からの要求では原則これを使う。
- **`lock_timeout`**：`SET LOCAL lock_timeout`で待ち時間の上限を決める。
- **`SKIP LOCKED`**：ロックを取れない行を飛ばし、残りの行をロックする。

## 悲観的ロック

HTTPの要求と応答をまたいでDBのトランザクションを保てないため、一覧を表示した時点で`SELECT ... FOR UPDATE`によりロックを取る方式は実現できない。
画面を開いた時点でロックする要件が出たら、まず要件を調整する。
どうしても必要な場合は、ロック用のテーブルを作ってアプリケーションでロックを再現し、ロックを解放するタイミングを同時に設計する。

## DBの行ロック

数量の更新のように、前回の値を確認する必要がない処理では、条件付きのUPDATE文と更新件数で判定してよい。

```sql
UPDATE t_stock SET stock_count = stock_count - 5
WHERE item_id = 1 AND stock_count >= 5;
```

更新件数が0件なら、在庫不足として利用者へ返し、業務判断を委ねる。
マスタの保守のように後勝ちの上書きを許容できない更新と、状態の遷移を伴う更新には使わない。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 楽観的ロックの手順を守る理由を書き直し、jOOQの楽観的ロックの機能を使わない規則を追加している。
