---
type: Convention
title: フロントエンドのビルドと配信
description: レンダリング方式とrouting mode、配信点が満たす条件、静的ファイルのブラウザcache、minifyとsource mapの扱いを定める規約。配信基盤を選ぶとき、配信点のheaderやcacheを設定するとき、build設定を変えるときに読む。
tags: [convention, frontend, build, delivery, cache, future-arch-guidelines]
---

# フロントエンドのビルドと配信

フロントエンドはCSRのSPAとしてbuildし、ブラウザのhistory APIでroutingする。
配信点は未知の画面pathに `index.html` を返し、任意のresponse headerを付けられる方式にする。
本番と検証環境のbuildはminifyし、source mapを公開しない。

## レンダリングとrouting

- 画面はCSR（Client Side Rendering）で描画し、SPAとして配信する（[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)）。
- SEOや初期表示の速度が強く求められる公開ページが必要になった場合は、SSR、SSG、それらとCSRの併用を、開発と運用のコストと併せてADRで判断する。
- routingはTanStack Routerのbrowser historyを使い、hash routingを使わない。

## 配信点の条件

- 配信点は、バックエンドへ振り分けるpath（`/api/**` など）を除く未知のpathに `index.html` を返す。
- バックエンドのpathの404を `index.html` で置き換えない。
- 配信点は、ADR-014が定めるsecurity headerを付けられる方式にする。
- CSPの`form-action`にIdPのoriginを加える。
  ログアウトのフォーム送信がIdPへredirectされ、ブラウザがredirect先にもform-actionを適用するためである。
- 新旧の静的ファイルを並べて置き、切り替えられる方式にする。
- インターネットに公開する場合はCDNとobject storage、閉域網で配信する場合はreverse proxyまたはweb serverを、基本の候補にする。
- HTTPSとresponse headerを扱えない静的website hostingの機能だけで配信しない。

## 静的ファイルのcache

- buildが出力するcontent hash付きのファイルは、長い `max-age` でブラウザにcacheさせる。
- `index.html` は `Cache-Control: no-cache` で毎回再検証させ、新しいbuildのファイル名を参照させる。

## minifyとsource map

- 本番と検証環境は `vp build` の本番buildでminifyし、検証環境のbuild設定を本番と同じにする。
- 本番と検証環境では、`build.sourcemap` を `true` にしない。
- error reportingのサービスへsource mapを送る場合は、`build.sourcemap: "hidden"` で生成し、送信した後に配信物から削除する。
- 開発用のデプロイ環境では、debugのためにsource mapを生成してよい。
- 閉域の社内システムで本番にsource mapを公開する場合は、セキュリティポリシーに照らしてADRで判断する。

## ローカル開発

ローカル開発ではVite proxyで同一originにし、CORSを使わない。
proxyの設定は[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)と[ADR-033](../adr/ADR-033-pin-vite-dev-origin-and-source-proxy-port.md)に従う。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
