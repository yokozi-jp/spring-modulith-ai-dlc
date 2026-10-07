---
type: Convention
title: 設計書と正本の分担
description: 機能の設計書に書く情報と、OpenAPI、Liquibase の changeset、ソースコード、デザインツールのファイルを正本として設計書に書き写さない情報の分担を定める規約。設計書を書くとき、API、テーブル、区分値、メッセージ、画面の見た目を設計書に書くか迷ったときに読む。
tags: [convention, writing, design-doc, documentation, future-arch-guidelines]
---

# 設計書と正本の分担

設計書には、正本から読み取れない情報だけを書き、正本の内容を書き写さない。
API の契約は OpenAPI、テーブル定義は Liquibase の changeset、区分値とメッセージはソースコード、画面の見た目はデザインツールのファイルを正本にする。
列が多く横に読めない表を Markdown で保守しない。

## 用語

- **設計書**：機能の目的、処理の流れ、業務ロジックを書く Markdown の文書。構成は[機能の設計書の構成](design-doc-feature-specs.md)に従う。
- **正本**：ある情報について、実装とレビューが従う唯一のファイル。

## 情報ごとの正本

設計書は次の正本へリンクし、正本にある内容を表や箇条書きで写さない。

- **Web API のパス、パラメータ、リクエストとレスポンスの項目、エラー応答**：コードから springdoc-openapi が生成する OpenAPI（[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)）。`openapi.yaml` を手で書かない。
- **単項目の入力検証**：リクエスト DTO の Bean Validation の制約と、そこから生成される OpenAPI スキーマ（[API の入力検証の配置](../web-api/validation.md)）。
- **テーブル、カラム、制約、インデックス**：Liquibase の changeset（[DB マイグレーション規約](../database/migrations.md)）。テーブル定義書を別に作らない。
- **区分値と名称の対応**：ソースコードまたは定義ファイル（[PostgreSQL の論理設計](../database/postgresql-logical-design.md)）。区分値の一覧表を設計書に作らない。
- **区分値の表示名と画面の固定文言**：フロントエンドの message catalog（[フロントエンドの国際化](../frontend/i18n.md)）。
- **API が返す文言と問題種別**：バックエンドの `MessageSource` と Problem Details の `type`（[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)、[ADR-016](../adr/ADR-016-localize-api-and-spa-messages.md)）。メッセージ定義表を設計書に作らない。
- **ログの event 名と属性**：ソースコード（[ログメッセージと属性の書き方](../observability/log-messages.md)）。
- **画面遷移、レイアウト、表示項目の見た目**：デザインツールのファイル。設計書にはファイルへのリンクだけを書く。
- **連携ファイルの仕様**：I/F のファイル定義書（[連携の一覧と定義書](../integration/interface-documentation.md)）。

## 正本にない情報

正本から読み取れない次の情報は、設計書またはそれぞれの一覧に書く。

- 処理の目的、処理の流れ、計算式、条件分岐の業務ルール。
- 複数項目の組み合わせや DB の参照が必要な入力検証の業務ルール。
- 区分値を導入した目的と、区分値で分岐する業務ルール。
- テーブルの保持期限は、[PostgreSQL のパーティションと改廃](../database/postgresql-partitioning-and-retention.md)の保持期限の一覧に書く。
- テーブルが個人情報を含むかどうかは、保持期限と同じ一覧に書く。

## 正本から作る補足

ER 図や処理対象テーブルの一覧のように、正本の内容を読みやすく示したいときは、補足として設計書に置いてよい。

- 補足の近くに、正本のファイルへのリンクと「正本は changeset である」のような一文を書く。
- 補足と正本が食い違ったら、正本に合わせて補足を直す。
- 図は[設計書の図](design-doc-diagrams.md)に従って書く。

## 列の多い表

列が多く、1行が画面の幅に収まらない表は、`git diff` で変更箇所を読めなくなるため Markdown で書かない。
次のどちらかで書き直す。

- 正本となる機械可読なファイル（OpenAPI、changeset、JSON Schema など）へリンクする。
- 表を責務ごとに分け、1つの表の列を減らす。

## 出典

- フューチャー株式会社「Markdown設計ドキュメント規約」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMarkdown/markdown_design_document.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
