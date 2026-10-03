---
type: Convention
title: データモデルを先に安定させる
description: 長く使うシステムでデータモデルの設計とレビューを先に行い、データモデルの不備をアプリケーションコードで隠さず直す方針と、用語、制約、参照クエリ、トランザクション境界の観点で確かめることを定める規約。業務テーブルを伴う機能を設計するとき、データモデルの不備をコードで回避したくなったときに読む。
tags: [convention, principles, data-modeling, database, future-arch-guidelines]
---

# データモデルを先に安定させる

短期で捨てる検証用のコードを除き、業務テーブルを伴う機能はデータモデルの設計とレビューを先に行う。
データモデルの不備は、アプリケーションコードで隠さず、マイグレーションでテーブルを直す。
データモデルでは、用語の統一、制約による業務ルールの表現、参照クエリの性能、トランザクション境界を確かめる。

## 対象

この規約は、業務テーブルを追加または変更する機能の設計に適用する。
アプリケーション内部の構造は[ADR-002](../adr/ADR-002-package-by-feature-onion-architecture.md)に従い、Domain Model を DB から独立させる。
データモデルを先に設計することは、Domain Model の設計を省いてよいことを意味しない。

## 設計の順序

- 業務テーブルの changeset は、それを使うアプリケーションコードより先にレビューを受ける（[Pull Requestの範囲と分割](../pull-request/pull-request-scope.md)）。
- データモデルに不備が見つかったら、アプリケーションコードでの変換や補正で吸収せず、changeset でテーブルを直す。手順は[DBマイグレーション規約](../database/migrations.md)に従い、稼働中のテーブルは[PostgreSQLのロックを抑えるスキーマ変更](../database/postgresql-online-schema-change.md)に従って変える。

## 確かめる観点

- **用語**：テーブルとカラムの名前を論物変換辞書でそろえ、同じ概念に同じ名前を使う（[PostgreSQLの命名規約](../database/postgresql-naming.md)）。業務用語は `GLOSSARY.md` に記録する（[ドメイン文書](../agents/domain.md)）。
- **制約**：業務ルールを、アプリケーションコードだけでなくテーブルの構造と制約でも表す。使う制約の種類は[PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md)に従う。
- **参照クエリ**：大量データの参照は性能の問題になりやすいため、想定する検索条件で引きやすい構造にする（[PostgreSQLの性能対策と負荷分散](../database/postgresql-performance.md)）。DB のスケールアップは最後の手段にする。
- **トランザクション境界**：同時に更新、参照されるデータの範囲を書き出し、モジュールの境界とスキーマの所有をその範囲に合わせる（[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)）。トランザクションの置き場所は[バックエンドの層の責務](../backend/layers.md)に従う。

## 出典

- フューチャー株式会社「アーキテクチャ原則ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPrinciple/principle_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
