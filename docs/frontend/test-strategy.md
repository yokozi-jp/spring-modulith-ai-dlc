---
type: Convention
title: テスト観点の割り当て
description: フロントエンドのテスト観点をunit test、component test、E2E test、手動確認のどれで確かめるかと、E2E testとアクセシビリティの自動検査の対象を定める規約。テスト計画を立てるとき、ある観点をどのテストで書くか決めるときに読む。
tags: [convention, frontend, testing, accessibility, future-arch-guidelines]
---

# テスト観点の割り当て

利用者から見える振る舞いをcomponent testで厚く検証し、unit testは純粋関数、E2E testは複数画面にまたがる主要な利用者の流れに限る。
どの観点をどのテストで確かめるかは、テスト計画として先に決める。

## 方針

**Testing Trophy**：利用者の視点で動作を確かめるcomponent testとintegration testを最も厚くし、実装の詳細に依存するunit testと費用の高いE2E testに偏らないテストの配分。

フロントエンドのテストはTesting Trophyに従う。
テストの配置、実行環境、MSW、coverageは[フロントエンドのテストと検証](testing.md)に従う。

## 観点ごとのテスト

- **業務ロジックとutility関数**：Node環境のunit testで検証する。
- **custom Hookと状態の内部ロジック**：Hookを使うcomponentを通して、component testで検証する。
- **操作による表示の変化**：component testで検証する。
- **component間の状態の連携**：component testで検証する。
- **画面遷移と遷移先の表示**：test用のrouterを使うcomponent testで検証する。
- **APIを使う取得と更新の流れ**：画面にロジックがある場合に、MSWを使うcomponent testで検証する。
- **複数画面にまたがる主要な利用者の流れ**：E2E testで検証する。
- **HTMLの構造とkeyboard操作**：component testでroleとlabelから操作して検証し、E2E testを導入した後はE2E testでも確かめる。
- **screen readerでの読み上げ**：手動で確認する。

## E2E testの対象

- unit testとcomponent testで確かめられる観点に、E2E testを書かない。
- E2E testの対象は、業務の根幹となる利用者の流れに要る画面と、複数のAPIとcomponentが組み合わさって壊れやすい複雑な画面に限る。
- E2E testの道具は、[ADR-027](../adr/ADR-027-adopt-frontend-testing-stack.md)のとおり要件が生じた時点で判断する。

## アクセシビリティの自動検査

- axe-coreなどの自動検査は、ADR-027のとおり要件が生じた時点で導入し、component testとE2E testに組み込む。
- 自動検査が検出できるのはWCAG違反の一部であるため、すべての観点の自動化を目標にしない。
- keyboard操作とscreen readerでの読み上げは、手動でも確認する。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
