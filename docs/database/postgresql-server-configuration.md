---
type: Convention
title: PostgreSQLのサーバー設定と拡張機能
description: 有効にする拡張機能、全文検索の手段の選び方、VACUUMの方針、ロケール、fillfactorとメモリなどのパラメータの決め方を定める規約。DBの環境を構築するとき、拡張機能を追加するとき、サーバーのパラメータを変えたくなったときに読む。
tags: [convention, database, postgresql, extensions, vacuum, configuration, future-arch-guidelines]
---

# PostgreSQLのサーバー設定と拡張機能

ステージングと本番では`pg_stat_statements`、`pg_hint_plan`、`auto_explain`、`pgAudit`を有効にする。
VACUUMは自動バキュームに任せ、`VACUUM FULL`を通常は実行しない。
ロケールはDBの作成前に決め、パラメータは既定値から始めて性能検証で必要になったものだけを変える。

## 拡張機能

ステージングと本番のPostgreSQLでは、次の拡張機能を有効にする。

- **`pg_stat_statements`**：SQLの実行統計を集め、性能の改善に使う。
- **`pg_hint_plan`**：ヒント句で実行計画を制御する。使い方は[PostgreSQLの性能対策と負荷分散](postgresql-performance.md)に従う。
- **`auto_explain`**：遅いSQLの実行計画をログに出す。設定は[PostgreSQLの監視](postgresql-monitoring.md)に従う。
- **`pgAudit`**：DBの操作を監査ログに記録する。設定は[PostgreSQLのロールと監査](postgresql-roles-and-audit.md)に従う。

必要に応じて、次の拡張機能を有効にする。

- **`pg_bigm`**：2-gramによる全文検索。部分一致検索に使う。
- **`btree_gist`**：スカラー値の等価条件を含む排他制約に使う。

ここにない拡張機能を追加するときは、利用するマネージドサービスで使えることを確認し、理由をPull Requestに書いてレビューで合意する。

## 全文検索の手段

カラムの部分一致検索のような要件は、構成要素を増やさずに済む`pg_bigm`で対応する。
`pg_bigm`で中間一致検索するときは、対象のカラムにGINインデックスを作る。

あいまい検索、検索結果の順位付け、入力中の候補表示が重要な場合は、OpenSearchなどの全文検索エンジンの導入を検討する。
全文検索エンジンへのデータの連携は[PostgreSQLと他のデータストアの連携](postgresql-data-integration.md)に従う。

## VACUUM

- VACUUMは手動で実行せず、自動バキュームに任せる。
- テーブルを参照も更新もできなくなる`VACUUM FULL`は実行しない。

次の場合に限り、手動で実行してよい。

- 更新と削除の多いテーブルで、自動バキュームが改廃の処理と重なって失敗し、すぐにVACUUMしたい場合は`VACUUM`を実行する。
- 一時的に大量の更新や削除を行い、未使用の領域で埋まったテーブルが再び大きくならない見込みの場合は、メンテナンスの時間帯に`VACUUM FULL`を実行する。

## ロケール

ロケール（`lc_collate`、`lc_ctype`）は、データベースの作成後に変更できない。
ソート順と文字列比較の要件を確認してから、データベースを作成する。
ローカル、CI、ステージング、本番で同じロケールを使う。

作成後に要件を満たせないと分かった場合は、次のどれかで対応する。

- `pg_dump`と`pg_restore`でデータベースを作り直す。
- 必要なSQLに`COLLATE`を付ける。
- `ALTER TABLE ... ALTER COLUMN ... SET COLLATION`でカラムごとに変え、そのカラムを使うインデックスを作り直す。

## fillfactor

- 追記だけで更新しないテーブルとそのインデックスは、`fillfactor`を100にする。
- それ以外のテーブルとインデックスは、`fillfactor`を90にする。

テーブルの既定値は100、インデックスの既定値は90であるため、更新するテーブルと追記だけのインデックスには明示的に指定する。
`fillfactor`の機械検査は、更新するテーブルかどうかをスキーマから判定できないため行わず、更新するテーブルと追記だけのテーブルをスキーマで区別する方法を定めるときに要否を決める。

## メモリとその他のパラメータ

- `shared_buffers`は、マネージドサービスが提供する初期値から変えない。
- `work_mem`はDBクラスタの設定を変えない。特定のSQLで並べ替えがディスクへあふれる場合に限り、レビューで合意してから、そのトランザクションで`SET LOCAL work_mem`を設定する。
- その他のパラメータは既定値を使い、性能検証で課題が見つかった時点で、レビューで合意してから変える。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
