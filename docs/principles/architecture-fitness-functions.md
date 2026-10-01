---
type: Convention
title: アーキテクチャの制約の自動検証
description: アーキテクチャの制約を目視のレビューではなく検査コード（適応度関数）として定義し、ローカルと CI で全変更に対して実行する方針を定める規約。新しい設計上の制約を決めたとき、アーキテクチャからの逸脱をレビューで繰り返し指摘しているときに読む。
tags: [convention, principles, architecture, ci, future-arch-guidelines]
---

# アーキテクチャの制約の自動検証

アーキテクチャの制約は、レビューの目視に頼らず、テストや静的解析の規則として定義する。
検査は開発中のローカルで実行でき、CI ではすべての Pull Request で実行してマージの条件にする。
新しい制約を決めたら、規約の文章と同じ変更で検査を追加する。

## 用語

- **適応度関数**：アーキテクチャが設計上の制約や非機能の目標を満たしているかを、自動で客観的に判定する検査。

## ルール

- 依存方向、配置、禁止 API のような構造の制約は、Spring Modulith の検証、ArchUnit、静的解析の規則として書く。現在の検査は[バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)にある。
- フロントエンドの import の制約は、違反が実在した時点で Lint の規則として追加する（[フロントエンドアーキテクチャ](../frontend/architecture.md)）。
- 検査は Task から実行し、ローカルと CI で同じコマンドを使う（[ADR-010](../adr/ADR-010-adopt-task-as-project-task-runner.md)）。実行場所は[Lintとテストのリファレンス](../tooling/lint-and-test.md)で確かめる。
- 検査はすべての Pull Request で実行し、失敗したらマージできないようにする（[main ブランチの保護設定](../repository/branch-protection.md)）。
- 違反は定期的な監査ではなく、コーディング中とローカルのビルドで分かるようにする。
- 検査で表せない制約は docs の規約に書き、レビューで確かめる。コーディングエージェントに一次レビューをさせる場合も、同じ docs を基準にする。

## 出典

- フューチャー株式会社「アーキテクチャ原則ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPrinciple/principle_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
