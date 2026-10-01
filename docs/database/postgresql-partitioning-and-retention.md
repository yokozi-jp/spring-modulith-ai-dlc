---
type: Convention
title: PostgreSQLのパーティションと改廃
description: パーティション化するテーブル、パーティションキーと分割方式、パーティションの追加と削除の手順、テーブル種別ごとの保持期限と改廃方法を定める規約。大量データのトランを設計するとき、保持期限を過ぎたデータを削除する仕組みを作るときに読む。
tags: [convention, database, postgresql, partitioning, retention, future-arch-guidelines]
---

# PostgreSQLのパーティションと改廃

数百万行を超え、改廃が必要なトランは、日付をキーにした宣言的パーティションのレンジ分割にする。
パーティション数は100程度に保ち、追加と削除はロックを抑える手順で行う。
保持期限はテーブル種別ごとに決め、改廃はパーティション単位の削除を基本にする。

## パーティション化するテーブル

データ量が数百万行を超え、改廃が必要なトランはパーティション化する。
行をDELETEで消すのではなく、パーティションごとDROPすることで、自動バキュームを誘発せずに改廃できる。

## パーティションの設計

- 宣言的パーティショニングを使い、テーブル継承によるパーティショニングを使わない。
- パーティション数は100程度に保つ。複数のパーティションを参照するクエリは、パーティション数に応じて実行計画の作成に時間がかかるためである。
- パーティションキーには、まず日付（年月日）のカラムを検討する。日次で100パーティションでは保持期間が足りない場合は、年月のカラムで月次にする。
- パーティションキーの値を更新しない。
- 日次や月次の分割にはレンジ分割を使う。リスト分割とハッシュ分割は、それに適した要件がある場合に使い、実データでパーティションごとの件数の偏りを確認する。
- パーティションをさらに分割するサブパーティションは使わない。

パーティションキーのカラムの型とNOT NULLは[PostgreSQLのデータ型](postgresql-data-types.md)に従う。
パーティションテーブルのインデックスの制約は[PostgreSQLの制約とインデックス](postgresql-constraints-and-indexes.md)に従う。

## パーティションの追加

稼働中のテーブルにパーティションを追加するときは、`CREATE TABLE ... PARTITION OF`ではなく、テーブルを作ってから`ATTACH PARTITION`する。
`CREATE TABLE ... PARTITION OF`は親テーブルをロックするためである。

```sql
CREATE TABLE t_sale_p2026_10 (LIKE t_sale INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
ALTER TABLE t_sale ATTACH PARTITION t_sale_p2026_10
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
```

## パーティションの削除

稼働中のテーブルからパーティションを削除するときは、`DETACH PARTITION ... CONCURRENTLY`で切り離してからDROPする。
直接のDROPと`CONCURRENTLY`なしのDETACHは、親テーブルに`ACCESS EXCLUSIVE`ロックを取るためである。

```sql
ALTER TABLE t_sale DETACH PARTITION t_sale_p2023_10 CONCURRENTLY;
DROP TABLE t_sale_p2023_10;
```

## 保持期限

保持期限は、テーブル種別ごとの方針を先に決め、テーブルごとの要件で個別に調整する。
法令を含む業務要件を満たした上で、ストレージの費用と過去データを調査する必要性の兼ね合いで長さを決める。
テーブル種別は[PostgreSQLのテーブル論理設計](postgresql-logical-design.md)に従う。

方針の例を示す。

- **マスタ**：無期限。改廃しない。
- **トラン**：3年。年月でパーティション化する。
- **ワーク、I/F受信ワーク、I/F送信ワーク**：7日。日次でパーティション化する。

## 改廃方法

トランとワークの改廃は、パーティション単位の削除で行う。
パーティション化できず改廃が必要なテーブルに限り、DELETEで改廃する。

DELETEで大量の行を削除した後は、必要に応じて次を行う。

- `ANALYZE`で統計情報を更新し、古い統計による実行計画の悪化を防ぐ。
- 数百万行を超える大きなインデックスに限り、インデックスを再作成する。メンテナンスの時間帯を確保できるなら、`VACUUM`を明示的に実行する方が有利な場合もある。

テーブルごとに、保持期限、改廃方法、自動化の有無、実行のタイミング、改廃の条件、`ANALYZE`と`VACUUM`の要否を一覧にまとめる。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
