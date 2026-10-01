---
type: Convention
title: ダークモード
description: 明暗の配色の定義方法と、OS設定への追従、手動の切替を加える場合の優先順位、保存先、適用の時機を定める規約。配色やthemeを変えるとき、ダークモードの切替を追加するときに読む。
tags: [convention, frontend, ui, theme, accessibility, future-arch-guidelines]
---

# ダークモード

明暗の配色はsemantic tokenの値として定義し、現在はOSの `prefers-color-scheme` に従って切り替える。
手動の切替を加える場合は、OSの設定を初期値にし、利用者が明示的に選んだ値をOSの設定より優先してlocalStorageに保存する。

## 配色の定義

- 明暗の配色は `style.css` のsemantic token（`--background` など）の値として定義し、componentに具体的な色を書かない（[ADR-025](../adr/ADR-025-adopt-shadcn-base-ui-and-tailwind.md)）。
- 現在はOSの設定に従い、`prefers-color-scheme: dark` のときにtokenの値を切り替える。
- 明暗の両方で、文字と操作要素の視認性とcontrastを確認する。
- 画像は明暗のどちらの背景でも見えるよう、背景を透過できる形式（SVG、PNG、WebPなど）にする。

## 手動の切替を加える場合

- 初期値はOSの設定にし、画面に手動の切替を置く。
- 利用者が切替で選んだ値は、その後にOSの設定が変わっても、OSの設定より優先する。
- 選んだ値はlocalStorageに保存し、使えない場合はmemoryへfallbackする（[ブラウザに保持するデータとキャッシュ](browser-storage-and-cache.md)）。
- 別のブラウザ、別のデバイス、別のdomainと設定を共有する要件がある場合だけ、設定をサーバーに保存する。
- サーバーが配色ごとに応答を変える必要がある場合だけ、設定をCookieで送る。
- 選んだ値はbundleしたscriptで最初の描画より前に適用する。CSPがinline scriptを許可しないため、`index.html` にinline scriptを書かない（[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)）。
- 切替を加える変更で、Tailwind CSSの `dark` variantと `style.css` のtokenの切替を、同じ属性またはclassに基づく指定へ揃えて変える。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
