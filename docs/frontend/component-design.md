---
type: Convention
title: componentの命名と設計
description: componentとそのファイル、props、event、ディレクトリの命名と、componentの責務の分け方、linkの文言と操作要素の大きさを定める規約。componentを追加するとき、componentを分割するか判断するときに読む。
tags: [convention, frontend, component, naming, accessibility, future-arch-guidelines]
---

# componentの命名と設計

componentはPascalCaseで役割または業務を表す名前にし、propsとeventはcamelCase、ディレクトリはkebab-caseにする。
一つのcomponentに過剰な責務を持たせず、業務ロジックを通常の関数へ分ける。
linkの文言は遷移先を表し、操作要素は十分なtarget sizeを確保する。

## 共通componentと業務component

- **共通component**：特定の業務に依存しないUI primitive。`components/ui` に置き、shadcn/uiのBase UI版で実装する。
- **業務component**：特定の業務機能のデータとロジックを持つcomponent。利用するfeatureの `components` に置く。

配置と依存方向は[フロントエンドのUIとスタイル](ui-and-style.md)と[フロントエンドアーキテクチャ](architecture.md)に従う。

## 命名

- component名はPascalCaseにし、業務（`UserProfileCard`、`OrderListItem`）または種類と役割（`SearchInput`）を表す。
- featureのcomponentのファイル名は、component名と同じPascalCaseにする（`OrderListItem.tsx`）。
- `components/ui` のファイル名は、shadcn CLIが生成する名前（`button.tsx`）に従う。
- propsはcamelCaseにし、eventを受けるpropsは `on` で始める（`onSubmit`）。
- ディレクトリ名と、独自に定義するCSS class名はkebab-caseにする。
- 他の開発者に通じない略語と曖昧な語を使わない。

## 責務の分け方

- 単一の責務を超えて多くを知り、多くを処理するcomponentは、責務ごとの小さいcomponentへ分ける。
- componentはpropsを境界にし、他のcomponentの内部に依存しない。
- 業務ロジックをcomponentに埋め込まず、React stateを使わない処理は通常の関数にする（[フロントエンドのルーティングと状態管理](routing-and-state.md)）。
- 単純な問題に複雑な構造を作らず、必要になった時点で複雑さを加える。

## アクセシビリティ

- アクセシビリティは設計の初期から要件に含め、WCAGに従う。
- linkの文言は遷移先または操作を表し、「ここをクリック」のような文言にしない。
- 操作要素はpaddingを含めて、WCAG 2.2の達成基準2.5.8（Target Size (Minimum)）の24×24 CSS pixel以上にする。
- native semanticsとBase UIのアクセシビリティの扱いは[フロントエンドのUIとスタイル](ui-and-style.md)に従う。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
