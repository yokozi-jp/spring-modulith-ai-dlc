---
type: Convention
title: コーディングエージェントを前提にした標準化
description: コーディングエージェントが少ない文脈で正確に変更できるよう、境界の切り方、ファイルの配置、文脈ファイル、用語集、レビューの基準をそろえる方針と、標準のパターンに乗らない機能の扱いを定める規約。モジュールやディレクトリの構成を決めるとき、エージェント向けの規約を追加するとき、標準のパターンに当てはまらない機能を実装するときに読む。
tags: [convention, principles, ai-assisted-development, future-arch-guidelines]
---

# コーディングエージェントを前提にした標準化

コードは、入力と出力を指定してエージェントに依頼できる小さな境界に分け、変更理由が同じファイルを同じ機能の下に置く。
アーキテクチャの概要、規約、禁止事項、業務用語は、機械が読める docs と用語集に書く。
標準のパターンに乗らない機能は、その箇所だけの独自実装として切り出し、標準から外れた理由を記録する。

## 境界と配置

- 機能は、入力と出力を明確にした関数、クラス、コンポーネントの単位に分ける。
- 変更理由が同じものを一つの機能の下にまとめ、他の機能への依存を減らす。バックエンドは[バックエンドアーキテクチャ](../backend/architecture.md)、フロントエンドは[フロントエンドアーキテクチャ](../frontend/architecture.md)の機能単位の構成に従う。

## 文脈ファイルと用語集

- アーキテクチャの概要、規約、禁止事項は docs に書き、steering と `AGENTS.md` から読む文書を案内する（[steering、docs、iwe の役割分担](../knowledge/knowledge-architecture.md)）。
- 業務固有の用語と、日本語の概念に対応する英語名は `GLOSSARY.md` に記録する（[ドメイン文書](../agents/domain.md)）。
- 変数名や関数名を一つずつ依頼文で指示せず、用語集と規約で決める。

## レビューの基準

- エージェントに一次レビューをさせる場合は、セキュリティとテストの観点を含む docs の規約を基準にする（[規約と仕様を分離して確認する code-review リファレンス](../agents/matt-pocock-skills/code-review.md)）。
- 機械的に検出できる観点は検査に寄せる（[アーキテクチャの制約の自動検証](architecture-fitness-functions.md)）。

## 標準に乗らない機能

- 標準のパターンや生成ツールで扱えない機能は、共通部品や生成ツールを拡張して全体を歪めず、その箇所だけの独自実装にする。
- 複雑な機能は、入出力を明確にした小さな関数やコンポーネントに分け、標準のパターンで書ける部分を増やす。
- 標準から外れた理由は、[ADR の運用ルール](../adr/conventions.md)の作成基準に当たる場合は ADR に書き、当たらない場合はその箇所のコメントか該当する領域の docs に書く。

## 出典

- フューチャー株式会社「アーキテクチャ原則ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPrinciple/principle_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
