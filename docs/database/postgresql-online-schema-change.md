---
type: Convention
title: PostgreSQLのロックを抑えるスキーマ変更
description: 稼働中の大きなテーブルへのスキーマ変更で取られるロックと、ロックや全行の走査を抑える手順を、カラム追加、型変更、NOT NULLの追加、インデックスの追加と変更ごとに定める規約。稼働中のテーブルを変更するchangesetを書くとき、その変更をレビューするときに読む。
tags: [convention, database, postgresql, migration, liquibase, future-arch-guidelines]
---

# PostgreSQLのロックを抑えるスキーマ変更

稼働中の大きなテーブルを変更するchangesetは、取られるロックと全行の走査の有無を確認してから書く。
NOT NULLの追加は検証を後回しにしたCHECK制約を経由し、インデックスは`CONCURRENTLY`で作る。
`CONCURRENTLY`を使うchangesetは`runInTransaction: false`にし、rollbackを定義する。

## 対象

この規約は、データを持つ稼働中のテーブル、特にパーティションテーブルを変更するchangesetに適用する。
マイグレーションの実行経路とrollbackの検証は[DBマイグレーション規約](migrations.md)に従う。
後方互換性を保つexpand-and-contractとデプロイの順序は[DBのデプロイと切り戻し](runbook-deploy-and-rollback.md)に従う。

## 変更ごとのロックと手順

- **テーブル名とカラム名の変更**：`ACCESS EXCLUSIVE`ロックを取るが、すぐに終わる。
- **文字列のカラムの桁数を上げる**：`ACCESS EXCLUSIVE`ロックを取るが、すぐに終わる。パーティションの子テーブル単位では実行できない。
- **数値のカラムの桁数を上げる**：`ACCESS EXCLUSIVE`ロックを取り、全行を走査する。遅い場合は、新しいカラムを追加し、データを複写し、古いカラムを削除して新しいカラムの名前を変える。この間、カラムが一時的に存在しなくなる。
- **既定値のあるカラムの追加**：`ACCESS EXCLUSIVE`ロックを取るが、既存の行は書き換えない。
- **既定値のないカラムの追加と値の設定**：カラムの追加後に大量のUPDATEが要る。処理を止められるなら、別のテーブルを作ってSELECT INSERTでデータを入れ、名前の変更で切り替える。

## NOT NULLの追加

既存のカラムへ直接`SET NOT NULL`すると、`ACCESS EXCLUSIVE`ロックを取ったまま全行を走査する。
次の手順で、全行の走査を弱いロックの間に行う。

```sql
-- 1. 既存の行を検証せずにCHECK制約を追加する
ALTER TABLE t_sale ADD CONSTRAINT chk_t_sale_item_id_not_null
    CHECK (item_id IS NOT NULL) NOT VALID;
-- 2. SHARE UPDATE EXCLUSIVEロックで既存の行を検証する
ALTER TABLE t_sale VALIDATE CONSTRAINT chk_t_sale_item_id_not_null;
-- 3. 検証済みのCHECK制約があるため、全行を走査せずにNOT NULLを付ける
ALTER TABLE t_sale ALTER COLUMN item_id SET NOT NULL;
-- 4. 不要になったCHECK制約を削除する
ALTER TABLE t_sale DROP CONSTRAINT chk_t_sale_item_id_not_null;
```

## インデックスの追加

稼働中のテーブルへのインデックスは`CREATE INDEX CONCURRENTLY`で作る。
`CONCURRENTLY`なしでは書き込みを止める`SHARE`ロックを取るためである。

パーティションテーブルの親には`CONCURRENTLY`を指定できない。
各パーティションに`CREATE INDEX CONCURRENTLY`でインデックスを作ってから、親テーブルにインデックスを作る。

## インデックスの列の変更

インデックスの列を変えるときは、同じ名前を保ったまま作り直す。

1. 既存のインデックスを`ALTER INDEX ... RENAME TO {インデックス名}_old`で改名する。
2. 元の名前で`CREATE INDEX CONCURRENTLY`する。
3. 改名した古いインデックスを削除する。

パーティションテーブルでは、インデックスの追加と同じ手順を組み合わせる。

## changesetの書き方

`CREATE INDEX CONCURRENTLY`と`DETACH PARTITION ... CONCURRENTLY`はトランザクションの中で実行できない。
これらを含むchangesetには`runInTransaction: false`を指定し、他の変更と同じchangesetに入れない。
`sql`変更で書く場合も、CIのrollback検証が通るよう`rollback`を定義する。

スキーマの差分からDDLを生成するツールを補助に使う場合も、生成されたDDLを必ずレビューし、ビュー、権限、型、名前の変更が漏れていないか確認する。
マイグレーションの適用はLiquibaseで行い、他のマイグレーションツールを使わない。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
