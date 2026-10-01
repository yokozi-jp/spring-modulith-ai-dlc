---
type: Convention
title: 画像とアイコン
description: 用途ごとの画像形式、画像の実装方法と代替テキスト、ファビコンの構成、OGPを設定する条件を定める規約。画像、アイコン、ファビコン、OGPを追加または変更するときに読む。
tags: [convention, frontend, image, accessibility, future-arch-guidelines]
---

# 画像とアイコン

ロゴとアイコンはSVG、写真と図版はAVIFまたはWebPにし、アニメーション画像を使わない。
画像には内容を伝える `alt` を付け、装飾だけの画像は `alt=""` にする。
ファビコンはSVGを基本にし、OGPは認証なしで共有される公開ページだけに設定する。

## 画像形式

- ロゴとアイコンはSVGにする。SVGを用意できない場合は、透過を保てるWebPまたはAVIFにする。
- 写真、製品画像、複雑なbanner、図版、screenshot、透過を含むUI要素は、AVIFまたはWebPにする。
- 高い色精度やHDRの表現が要る場合は、AVIFにする。
- アニメーション画像は使わず、CSSのアニメーション、JavaScriptによる動き、または動画にする。どうしても必要な場合は、WebP、AVIF、APNGにする。

## 実装

- 新しい形式に対応しないブラウザも対象にする場合は、`<picture>` で形式ごとの候補を並べ、内側の `<img>` をfallbackにする。
- 表示幅が変わる画像は、`srcset` と `sizes` で解像度ごとの候補を渡す。
- 配置する前に画像を圧縮し、Exifなどの不要なmetadataを削除する。
- 初期表示の領域の外にある画像には `loading="lazy"` を付ける。
- `<img>` の `alt` には、画像の内容または機能を簡潔に書く。
- 装飾だけで情報を持たない画像は `alt=""` にし、支援技術に読み飛ばさせる。
- 別のoriginから画像を読む場合は、CSPの `img-src` への追加を[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)に従って判断する。

## ファビコン

- ファビコンはSVGを基本にし、`public/favicon.svg` を `index.html` から参照する。
- iOSのhome画面への追加を想定する場合は、180×180のPNGを `apple-touch-icon` として追加する。
- PWAとしてinstallさせる場合は、192×192と512×512のPNGと、それらを参照する `manifest.webmanifest` を追加する。
- ICO形式は旧来のブラウザを対象にする場合だけ追加し、32×32の画像を一つだけ入れる。

## OGP

- 認証が必要な画面と社内網だけで使う画面には、OGPを設定しない。リンクを展開するのは共有先サービスのサーバーであり、その画面へ到達できないためである。
- 認証なしで公開し、SNSで共有されるページには、`og:title`、`og:description`、`og:image`、`og:url` を設定する。
- 共有先サービスのサーバーは通常JavaScriptを実行しないため、SPAでページごとのOGPが必要になった場合は、配信方式と併せて判断する。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
