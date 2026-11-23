---
type: ADR
title: 'ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む'
description: フューチャー株式会社のアーキテクチャ設計ガイドラインを、原文を転載せず、このリポジトリの規約と ADR に合わせて責務ごとの docs 文書へ書き直して取り込む決定。
tags: [adr, documentation, knowledge-management, future-arch-guidelines]
---

# ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む

## Status

Proposed

このうち AWS の取り込みは [ADR-045](ADR-045-remove-aws-docs-and-production-cd-example.md) で取り消した。
ただし、CloudWatch Logs のアプリケーションのロググループの規約は[可観測性データの規約](../observability/conventions.md)へ移した。

## Date

2026-10-01

## Context

Web API、PostgreSQL、非同期処理、システム間連携、ログ、フロントエンド、非機能要件、性能テスト、帳票などの設計規約は、docs にまだないか、ADR の決定だけで規約文書がない。
フューチャー株式会社は、これらの主題を扱う「アーキテクチャ設計ガイドライン」を CC BY 4.0 で公開している。
このライセンスでは、出典、ライセンス、改変の有無を示せば改変と再配布ができる。

原文は30本、約3.8万行あり、1本が400行から3,500行ある。
そのまま置くと、[ADR-038](ADR-038-route-steering-to-docs-knowledge.md) の1ファイル1責務と、[日本語技術文書の文章規範](../writing/japanese-tech-writing.md) に合わない。
原文には、このリポジトリの ADR と矛盾する記述がある。
たとえば Git ブランチフローは develop ブランチと release ブランチを使い、[ADR-017](ADR-017-adopt-trunk-based-repository-governance.md) のトランクベース開発と両立しない。
Terraform や DynamoDB のように、このリポジトリで使うと決めていない技術を前提にする原文もある。
一方、本番デプロイのワークフロー例（[ADR-045](ADR-045-remove-aws-docs-and-production-cd-example.md) で削除）は、ECR、ECS、ALB、RDS などの AWS 構成を仮定している。

バッチ、メール、データガバナンスは、当初は取り込まない原文に入れていた。
次の決定により、取り込む側へ移した。
ADR が Proposed の間は Decision を直接直す（[ADR の規約](conventions.md#ライフサイクル)）。

- バッチ：ADR と規約にバッチを前提にした規則があるのに、ジョブの入口、冪等性と再開、二重の起動を定める文書がない（[#314](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/314) の調査、[#558](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/558)）。
- メール：テナントの管理者が利用者をメールで招待すると決め、「使うと決めていない技術」でなくなった（[#323](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/323) の調査、[#572](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/572)）。SMS は、電話番号を集めず、SMS を送る機能もないため、取り込まないまま残す。
- データガバナンス：データ基盤と、部署をまたぐ二次利用者がないため、論理名のコメントと個人データの規則の2つだけを取り込む（[#326](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/326) の調査、[#575](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/575)）。

3つの Issue が同じ行を直すため、書き直しは [#597](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/597) にまとめた。

## Decision

原文を転載せず、このリポジトリの規約に合わせて書き直した文書として `docs/` に置く。

- 取り込む原文は、Web API、PostgreSQL、非同期、I/F、ログ、Web フロントエンド、テクニカルライティング、Markdown 設計ドキュメント、アーキテクチャ原則、コードレビュー、性能テスト、非機能要件、帳票、AWS、バッチ、メール、データガバナンスとする。
- データガバナンスは、表と列の論理名のコメントと、業務に要らない個人データの列を作らない規則の2つだけを取り込む。
- Git ブランチ、Terraform、DynamoDB、SMS、Slack、ソフトスキル、データマネジメントは取り込まない。データガバナンスのうち、データ分類、マスキングの基準、データカタログ、品質の SLA、推進体制、AI-Ready も取り込まない。
- 原文の各ルールは、このリポジトリに当てはまり、既存の ADR と規約に矛盾しないものだけを採用する。既存の ADR と規約が原文より優先する。
- 既存の docs と同じ内容は書き写さず、既存文書へリンクする。
- 採用したルールは、既存の領域か新しい領域に、1ファイル1責務の文書として書く。
- 各文書の末尾に「出典」節を置き、原文のタイトル、URL、取り込んだ commit、CC BY 4.0、改変している旨を書く。
- 取り込む原文の版は commit `e309a6d04646be709612cfe82962c8f330b35857`（2026-09-30）に固定する。原文の更新への追従は、必要になった時点で差分を確認して行う。

## Consequences

### Positive

- 規約がなかった主題について、エージェントと開発者が参照できる規約文書がそろう。
- 原文の判断のうち、このリポジトリに合うものだけが docs に残り、ADR と矛盾する規約が混ざらない。
- 文書が責務ごとに分かれるため、エージェントが必要な文書だけを選んで読める。

### Negative

- 書き直しの過程で、原文の意図を取り違える、または必要なルールを落とす可能性がある。
- 原文が更新されても自動では反映されず、差分の確認に手作業が要る。
- 新しい領域が増え、領域ごとの steering と index の保守対象が増える。

### Neutral

- 取り込んだ規約のうち、後戻りしにくい技術選定を伴うものは、実装時に個別の ADR を起こす。
- 取り込まなかった原文が必要になったら、同じ方針で追加する。

## Alternatives Considered

### 選択肢1: 原文を全文転載する

- **Description**：原文の Markdown を `docs/` 配下へそのまま置く。
- **Pros**：作業量が少なく、原文との差分を追いやすい。
- **Cons**：1ファイル1責務と文章規範に合わず、既存の ADR と矛盾する記述が規約として残る。

### 選択肢2: 原典へのリンク集だけを置く

- **Description**：`docs/` に原文ページへのリンクと「いつ読むか」だけを書く。
- **Pros**：保守がほぼ要らない。
- **Cons**：どのルールがこのリポジトリに当てはまるかが決まらない。iwe の検索とリンク検査の対象にならず、エージェントが原文を取得できない環境では参照できない。

### 選択肢3: 取り込まない

- **Description**：規約は必要になった時点で一から書く。
- **Pros**：使わない規約を抱えない。
- **Cons**：主題ごとに一から調べる手間がかかり、公開された知見を活かせない。

## References

- [アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/)
- [future-architect/arch-guidelines](https://github.com/future-architect/arch-guidelines)
- [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- [ADR-038: steering をナビゲーションに限定し、規約の正文を docs/ に置く](ADR-038-route-steering-to-docs-knowledge.md)
- [docs 文書の作成と変更](../knowledge/documentation-authoring.md)
