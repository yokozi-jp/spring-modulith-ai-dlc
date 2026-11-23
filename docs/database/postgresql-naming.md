---
type: Convention
title: PostgreSQLの命名規約
description: 論物変換辞書、表と列の論理名のコメント、予約語、モジュール名とスキーマ名の予約語、テーブル種別の接頭辞、カラム名の接尾辞、インデックス名、外部キー名、RLSのポリシー名、シーケンス名の付け方を定める規約。テーブル、カラム、インデックス、外部キー、シーケンスを追加するとき、物理名を決めるときに読む。
tags: [convention, database, postgresql, naming, future-arch-guidelines]
---

# PostgreSQLの命名規約

物理名は論物変換辞書から組み立て、テーブル名とカラム名にSQLのキーワードを、モジュール名とスキーマ名にSQLの予約語を使わない。
業務テーブルと、そのカラムには、日本語の論理名をコメントで付ける。
テーブル名は種別の接頭辞を付けた単数形にし、カラム名は値の種類を表す接尾辞で終える。
インデックス名、外部キー名、シーケンス名にはテーブル名を含め、RLSのポリシー名は`tenant_isolation`にする。

## 対象

この規約は、モジュールが所有する業務テーブルと、そのインデックス、制約、RLSのポリシー、シーケンスに適用する。
Spring Modulithのイベント出版テーブルのように、フレームワークが名前を定めるオブジェクトには適用しない。
スキーマ名はモジュール名と一致させ、[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)に従う。

## 論物変換辞書

**論物変換**：業務用語の論理名を物理名へ変換すること。

業務用語を英訳するかローマ字にするかはチームで一つに決め、論理名と物理名の対応を論物変換辞書で管理する。
辞書の論理名は`GLOSSARY.md`の用語と一致させる。
辞書と`GLOSSARY.md`の一致の機械検査は、辞書がまだないため行わず、辞書を作るときに要否を決める。

- 辞書には、それ以上分解できない単語から登録する。
- 「商品一覧」のような合成語は登録せず、「商品」と「一覧」を登録する。
- 専門用語のように分解すると意味が変わる語と、合成すると長くなりすぎる語に限り、合成語を一語として登録する。

PostgreSQLの識別子は63バイトを超えると切り詰められる。
物理名は辞書の単語から短く組み立てる。

## 論理名のコメント

業務テーブルと、そのすべてのカラム（共通カラムを含む）には、日本語の論理名をコメント（`COMMENT ON`）で付ける。
表と列を作るchangesetで、最初から書く。

- コメントには、最低限、論理名（例：`注文`、`注文日時`）を書く。説明を書き足すことは求めない。
- 論理名は`GLOSSARY.md`の用語にそろえる。
- 論物変換辞書を作るときは、辞書の論理名とコメントを一致させる。

コメントはchangesetの`remarks`に書く。
書き方は[DBマイグレーション規約](migrations.md#論理名のコメント)に示す。
jOOQのコード生成は、コメントを生成物のJavadocと`getComment()`に写す。

付け忘れは、スキーマ検査の規則N12が止める（[バックエンドのアーキテクチャテスト](../backend/architecture-tests.md#db)）。

## 使わない名前

[PostgreSQLのSQLキーワード一覧](https://www.postgresql.org/docs/current/sql-keywords-appendix.html)にあるキーワードは、予約語かどうかに関係なく、テーブル名とカラム名に使わない。
物理名は引用符を必要としない小文字とアンダースコアで書く。
テーブル名と、そのテーブルのカラム名を同じにしない。

モジュール名とスキーマ名には、SQLの予約語を使わない（[ADR-069](../adr/ADR-069-avoid-sql-reserved-words-in-module-names.md)）。
予約語かどうかは、同じキーワード一覧の表で、PostgreSQLの列がreservedの語として確かめる（`order`はreservedのため使わず、non-reservedの`ordering`は使える）。
スキーマ名はモジュール名と一致する（[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)）ため、予約語にすると手書きのSQLで毎回引用符が要る。
付け忘れは実行するまで分からず、引用符の有無で大文字と小文字の扱いも変わる。

## テーブル名

テーブル名は、種別を表す接頭辞の後に単数形の名前を続ける（`orders`ではなく`t_order`）。
種別の定義は[PostgreSQLのテーブル論理設計](postgresql-logical-design.md)に従う。

- **マスタ**：`m_`（例：`m_item`）
- **トラン**：`t_`（例：`t_sale`）
- **ワーク**：`w_`（例：`w_item`）
- **I/F受信ワーク**：`wr_`（例：`wr_item`）
- **I/F送信ワーク**：`ws_`（例：`ws_item`）
- **サマリ**：`s_`（例：`s_sale`）
- **履歴**：`h_`（例：`h_sale`）
- **ビュー**：`v_`（例：`v_m_item`）
- **マテリアライズドビュー**：`mv_`（例：`mv_m_item`）
- **postgres_fdwで他システムへ公開するビュー**：`fdw_`（例：`fdw_m_item`）
- **一時テーブル**：`tmp_`（例：`tmp_item`）

集計の粒度が異なるサマリが複数あるときは、日次を`sd_`、週次を`sw_`、月次を`sm_`とする。

## カラム名

カラム名は、値の種類を表す接尾辞または接頭辞を付ける。

- **日付**：`{名前}_date`（例：`order_date`）
- **絶対時刻**：`{名前}_at`（例：`ordered_at`）
- **名称**：`{名前}_name`（例：`item_name`）
- **ID**：`{名前}_id`（例：`item_id`）。画面に表示しないサロゲートキーに使う。
- **コード**：`{名前}_code`（例：`item_code`）。画面に表示しうるナチュラルキーに使う。
- **フラグ**：`is_{名前}`または`has_{名前}`（例：`is_expired`）
- **区分**：`{名前}_typ`（例：`item_category_typ`）
- **単位を持つ数値**：`{名前}_{単位}`（例：`route_distance_km`、`duration_min`）
- **件数や数量**：`{名前}_count`（例：`order_count`）
- **番号**：`{名前}_no`（例：`line_no`、`lock_no`）。表示する順序などの連番と、排他制御の版番号に使う。

## インデックス名

インデックス名はスキーマ内で一意にするため、テーブル名を含める。

- **主キー**：`pk_{テーブル名}`
- **ユニークインデックス**：`uk_{テーブル名}`。同じテーブルに複数あるときは`uk_{連番}_{テーブル名}`とする。
- **その他のインデックス**：`idx_{連番}_{テーブル名}`

物理名が長くなりやすい業務領域では、`idx_`の代わりに`i{連番}_{テーブル名}`を使う。

## 外部キー名とポリシー名

外部キー名には、参照する側のテーブル名を含める。
RLSのポリシー名はテーブルごとに一意であればよく、業務テーブルにポリシーを1つだけ作るため、どの表でも同じ名前にする。

- **外部キー**：`fk_{テーブル名}`。
  同じテーブルに複数あるときは`fk_{連番}_{テーブル名}`とする（例：`tenancy.m_membership`の`fk_m_membership`と`fk_2_m_membership`）。
- **RLSのポリシー**：`tenant_isolation`（[PostgreSQLのマルチテナント設計](postgresql-multitenancy.md#rlsでテナントを分ける業務テーブル)）。

外部キー名はchangesetのlint（規則K4）、ポリシー名はRLSのSQLのlint（規則S1）が確かめる（[バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)）。

## シーケンス名

IDENTITY列が暗黙に作るシーケンスは、PostgreSQLの自動命名に任せる。
シーケンスを明示的に作るときは、`seq_{テーブル名}_{連番}`とする。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- フューチャー株式会社「データガバナンス設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDataGovernance/data_governance_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)。「論理名のコメント」の節が、`description`を最初から必ず書き、最低限は日本語の名前にする推奨を取り込んでいる。
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
