---
type: ADR
title: 'ADR-113: 論理削除フラグを作らず、業務上の意味を持つカラムか物理削除で表す'
description: 業務テーブルに論理削除フラグを作らず、削除を業務上の意味を持つカラムか物理削除で表す理由を記録する決定。理由を取り込み元の記述、このリポジトリのコードと ADR、一般論に分けて示す。
tags: [adr, database, postgresql, data-modeling]
---

# ADR-113: 論理削除フラグを作らず、業務上の意味を持つカラムか物理削除で表す

## Status

Proposed

## Date

2026-11-22

## Context

[PostgreSQLのテーブル論理設計](../database/postgresql-logical-design.md#論理削除)は、論理削除フラグを作らないと定めるが、守る理由を書いていなかった（[issue #610](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/610)）。
この規約は、フューチャー株式会社の PostgreSQL 設計ガイドラインの取り込み（[ADR-040](ADR-040-import-future-architecture-guidelines.md)）で入った。
ADR-040 の本文は、この規約の理由を書いていない。
[ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md) に「論理削除」の語はあるが、ORM を作らない理由の一部であり、フラグを禁じる理由ではない。
この ADR は、すでにある規約の理由を後から記録する。
理由は出所で三つに分ける。

**取り込み元の記述**（ガイドライン commit `e309a6d` の「論理削除」）

ガイドラインは、論理削除フラグを避けることが一般に推奨されると述べ、不都合を三つ挙げる。

- クエリの条件に `deleted_flg` が増え、可読性と保守性が下がる。
- 性能が下がる懸念がある。
- 一意制約など、整合性を保ちにくくなる。

ガイドラインは三つの機構を説明していない。
性能の懸念は、ガイドラインの記述にとどまり、このリポジトリで測っていない。

このリポジトリのコードと ADR で確かめたことを次に示す。

- 論理削除のカラム名（`deleted_flg`、`is_deleted`、`deleted_at` など）は、changeset の lint の規則 N11（`ChangesetTableRules`）が拒否する。
- 同じモジュールの表どうしの参照には外部キーがあり、`ON DELETE` の動作を付けない（[ADR-108](ADR-108-add-tenant-composite-foreign-keys-within-modules.md)）。
  外部キーが止めるのは、参照先の行が存在しない場合である。
  削除済みの印を付けた親の行は存在し続けるため、外部キーは、その親を指す子の行を止めない（ADR-108 の本文からの推論で、動作は確かめていない）。
- トランとワークの改廃は、パーティション単位の DROP を基本にする（[PostgreSQLのパーティションと改廃](../database/postgresql-partitioning-and-retention.md#改廃方法)）。
  論理削除の行は、保持期限が過ぎても残るため、この改廃の対象から外れる（規約の記述からの推論で、動作は確かめていない）。
- テナントの分離は RLS が行い、ポリシーは `tenant_public_id` だけを比べる（[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md)）。
  削除済みの行を除く条件は、RLS のポリシーに入っていない。
  論理削除を入れると、その条件をポリシーかすべてのクエリに足すことになる（推論で、確かめていない）。

**一般論**（このリポジトリの判断として確かめていない）

- 削除済みの行を除く条件の付け忘れが、削除済みのデータを画面や集計に出す。
- 削除済みの行が残るため、業務キーのユニーク制約が、同じ業務キーでの再登録とぶつかる。
- 「削除」が、取り消し、退会、非表示のどれを指すのか曖昧になる。
- 行が増え続ける。

## Decision

業務テーブルに、論理削除フラグを作らない。
削除を表したい場合は、次の順で検討する。

1. 退職、既読、有効期間切れ、廃盤、承認状態のように、業務上の意味を持つカラムで表す。
2. 本当に削除する要件なら物理削除し、誤操作からの復元や監査での参照が要件にあるときだけ、アーカイブテーブルへ退避する。

現行の規約文書の記述は、この決定と変わらない。

## Consequences

### Positive

- クエリに削除済みの行を除く条件を足さずに済み、付け忘れによる誤表示が起きない。
- 業務キーのユニーク制約を、削除済みの行を含めずに保てる。
- 保持期限を過ぎた行は、パーティションの DROP で消せる。

### Negative

- 物理削除した行は、アーカイブテーブルへ退避しないかぎり、復元できない。
- 誤操作からの復元や監査での参照が要件になるたびに、アーカイブテーブルか、業務上の意味を持つカラムの設計が要る。
- 業務上の意味を持つカラムは、削除の種類ごとに設計する手間がかかる。

### Neutral

- 法令や契約でアーカイブなしの削除が求められる場合は、退避せずに物理削除する。
- 規則 N11 は、カラム名の正規表現で検出する。別の名前で同じ意味を持つカラムは、レビューで確かめる。

## Alternatives Considered

### 選択肢1: 論理削除フラグを標準にする

- **Description**：すべての業務テーブルに `deleted_flg` か `deleted_at` を持たせ、削除をフラグの更新で表す。
- **Pros**：削除した行を後から復元でき、監査で参照できる。
- **Cons**：上に挙げた、クエリの条件の増加、整合性の維持の難しさ、改廃の対象から外れる問題を、すべての表が負う。

### 選択肢2: 必要なテーブルだけ、論理削除を許す

- **Description**：復元や監査の要件があるテーブルに限り、論理削除フラグを認める。
- **Pros**：要件のある表だけが、フラグの費用を負う。
- **Cons**：復元と監査の要件だけなら、アーカイブテーブルへの退避で満たせる。フラグを許す基準が、表ごとの判断になる。

## References

- [issue #610](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/610)
- [PostgreSQLのテーブル論理設計](../database/postgresql-logical-design.md)
- [アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)（CC BY 4.0、commit `e309a6d`）
- [SQLアンチパターン 幻の第26章「とりあえず削除フラグ」](https://www.slideshare.net/slideshow/ronsakucasual/52256922)（ガイドラインが参考に挙げる文書。この ADR では内容を確かめていない）
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-108: 同じモジュールの表どうしの参照に、テナントを含む複合の外部キーを作る](ADR-108-add-tenant-composite-foreign-keys-within-modules.md)
