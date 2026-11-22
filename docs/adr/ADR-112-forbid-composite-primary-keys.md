---
type: ADR
title: 'ADR-112: 複合主キーを使わず、業務キーの組にはユニークインデックスを張る'
description: 業務テーブルの主キーを単一列のサロゲートキーにし、複合主キーを使わない理由を記録する決定。適用期間付きのマスタも別のサロゲートキーを主キーにし、業務キーと適用終了日の組をユニークにする。理由を取り込み元の記述、このリポジトリのコードと ADR、一般論に分けて示す。
tags: [adr, database, postgresql, primary-key]
---

# ADR-112: 複合主キーを使わず、業務キーの組にはユニークインデックスを張る

## Status

Proposed

## Date

2026-11-22

## Context

[PostgreSQLの主キー](../database/postgresql-primary-keys.md)は「複合主キーは使わない」と定めるが、守る理由を書いていなかった（[issue #610](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/610)）。
この規約は、フューチャー株式会社の PostgreSQL 設計ガイドラインの取り込み（[ADR-040](ADR-040-import-future-architecture-guidelines.md)）で入った。
ADR-040 と [ADR-089](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md) の本文は、複合主キーの理由を書いていない。
この ADR は、すでにある規約の理由を後から記録する。
理由は出所で三つに分ける。

**取り込み元の記述**（ガイドライン commit `e309a6d` の「ナチュラルキー / サロゲートキー」）

- 適用期間付きのマスタで、別のサロゲートキーと適用終了日の組を主キーにできる場合も、フロントエンドからの扱いやすさを考えて、別のサロゲートキーを払い出し、複合キーを使わない。
- 単価マスタの例で、主キーを「商品ID と取引先ID」にすると、主キーの粒度が変わる要件変更が起きたときに作り直しになる。ガイドラインはこの可能性がゼロではないとして、非推奨にしている。
- 業務キーを主キーにすると、コード体系の変更が主キーの更新になって性能の問題になりやすく、業務キーの増減が結合処理の全体に条件を足す。

ガイドラインは複合主キーを禁じる専用の理由を一つにまとめていない。
上の三点は、サロゲートキーを選ぶ理由とフロントエンドの扱いやすさから読み取れるものである。
一方でガイドラインは、複数の事業部が別々に業務キーを採番する場合に、部門コードを含む複合主キーが必要になりうると認めている。

このリポジトリのコードと ADR で確かめたことを次に示す。

- `AggregateTable.id` は `TableField<R, Long>`、`ChildTable.parentKey` は `TableField<C, Long>` である。`TableWriter` は、単一の `Long` の主キーを前提にする。複合主キーのテーブルは宣言できない。
- 同じモジュールのテーブルどうしは、主キーの列で参照する（[PostgreSQLの主キー](../database/postgresql-primary-keys.md#主キーと公開用idの使い分け)）。モジュールの外に出る値は、単一の UUID の `public_id` で表す（ADR-089）。主キーが組になると、この二つの規則に当てはまらない。
- 業務テーブルの主キーは、changeset を確かめた範囲（001 から 024）で、すべて単一列である。複合主キーは、カタログの検査の規則 P1（`SchemaTableConventions`）が拒否する。
  フレームワークがスキーマを定める `spring_session_attributes` は複合主キーを持つが、この規約の対象外である（[PostgreSQLの主キー](../database/postgresql-primary-keys.md#対象)）。
- テナントの分離は主キーに `tenant_public_id` を含めず、RLS で行う（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。複数の事業部の業務キーの重複を複合主キーで避けるガイドラインの例は、この方式では必要にならない。
- 複合の外部キーは残る。[ADR-108](ADR-108-add-tenant-composite-foreign-keys-within-modules.md) の `(tenant_public_id, 参照先の主キー)` は、参照先の主キーとは別に張った同じ組のユニークインデックスを指す。主キーは単一列のままである。

**一般論**（このリポジトリの判断として確かめていない）

- 主キーが複数列だと、子の外部キーも複数列になり、結合条件と SQL が長くなる。
- 業務キーを主キーに含めると、その値の変更が子の外部キーへ波及する。
- URL と API の識別子が組になる。

## Decision

業務テーブルの主キーは、単一列のサロゲートキーにする。
複合主キーは使わない。
適用期間付きのマスタも、別のサロゲートキーを主キーにし、業務キーと適用終了日の組にユニークインデックスを張る。
業務キーの一意性は、ユニークインデックスで保つ。

## Consequences

### Positive

- `TableWriter`、`ChildTable`、Repository が、主キーを単一の `Long` として扱える。
- 外部キー、JOIN、API の識別子が単一列で済む。
- 業務キーの構成や粒度が変わっても、主キーと子の外部キーは変わらず、ユニークインデックスの作り直しで済む。

### Negative

- 適用期間付きのマスタに、業務上の意味を持たないサロゲートキーの列と、組のユニークインデックスが増える。
- 業務キーが再利用されうる場合（過去の ISBN のように）は、ユニークインデックスも張れない。
  そのときは、ユニークでないインデックスにするしかない。
- 主キーを見ても、業務上の一意性はわからない。ユニークインデックスを読む必要がある。

### Neutral

- 複合の外部キーは、ADR-108 のとおり残る。この ADR は主キーだけを定める。
- 複合主キーが必要な要件が出たときは、`TableWriter` の `Long` 前提と規則 P1 を改める判断を、新しい ADR で行う。

## Alternatives Considered

### 選択肢1: 適用期間付きのマスタに限り、サロゲートキーと適用終了日の複合主キーを許す

- **Description**：ガイドラインが「複合主キーとしたい場合もある」と挙げる商品マスタの形（商品ID と適用終了日）を認める。
- **Pros**：主キーの列が増えず、適用期間の一意性が主キーで表せる。
- **Cons**：`TableWriter` の `Long` 前提に合わない。フロントエンドの扱いやすさを理由に、ガイドライン自身も採らない。

### 選択肢2: テナントや事業部のコードを主キーに含める

- **Description**：`tenant_public_id` を主キーの先頭に含める。
- **Pros**：業務キーの重複を主キーで避けられる。
- **Cons**：テナントは RLS で分ける（ADR-107）ため、主キーで分ける必要がない。主キーが組になり、この ADR の Positive が失われる。

### 選択肢3: 業務キーをナチュラルキーの主キーにする

- **Description**：商品コードのように変わらないと確信できる業務キーを主キーにする。
- **Pros**：カラムが増えず、業務上の概念と一致させやすい。
- **Cons**：コード体系の変更が主キーの更新になり、子の外部キーに波及する。業務キーが複数列なら、複合主キーに戻る。

## References

- [issue #610](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/610)
- [PostgreSQLの主キー](../database/postgresql-primary-keys.md)
- [アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)（CC BY 4.0、commit `e309a6d`）
- [サロゲートキーと複合主キー | DBFlute](https://dbflute.seasar.org/ja/manual/topic/dbdesign/surrogatekey.html)（ガイドラインが参考に挙げる文書。この ADR では内容を確かめていない）
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-089: 主キーをモジュールのスキーマと Repository の中に閉じ、外に出る値には例外なく public_id を使う](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)
- [ADR-107: 業務テーブルを RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- [ADR-108: 同じモジュールの表どうしの参照に、テナントを含む複合の外部キーを作る](ADR-108-add-tenant-composite-foreign-keys-within-modules.md)
