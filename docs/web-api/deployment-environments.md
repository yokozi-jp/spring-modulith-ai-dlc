---
type: Convention
title: APIのデプロイ環境とデプロイ方式
description: APIを公開するデプロイ環境の識別方法と、ダウンタイムの要件に応じたデプロイ方式の選択を定める規約。新しいデプロイ環境のドメインを決めるとき、APIのリリース方式を決めるときに読む。
tags: [convention, web-api, deployment, future-arch-guidelines]
---

# APIのデプロイ環境とデプロイ方式

デプロイ環境はサブドメインで識別し、パスや環境ごとの接頭辞で区別しない。
メンテナンスウィンドウを確保できる場合はインプレースデプロイ、ダウンタイムを許容できない場合はローリングアップデートかブルーグリーンデプロイメントを使う。

## 環境の識別

本番以外のデプロイ環境は、環境名をサブドメインに置いて識別する。

- **本番**：`example.com`
- **開発**：`dev.example.com`
- **検証**：`stg.example.com`

どの環境でも、SPAとAPIは[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)に従い同じオリジンで公開し、APIは`/api`配下に置く。
環境ごとに変わる値は、[ADR-008](../adr/ADR-008-single-application-yaml-external-config.md)に従い外部から注入する。

## デプロイ方式

- **インプレースデプロイ**：メンテナンスウィンドウを確保できる場合に使う。
- **ローリングアップデート**または**ブルーグリーンデプロイメント**：要件としてダウンタイムを許容できない場合に使う。

新旧のバージョンが同時に動く方式では、移行中の旧クライアントが新しいAPIを呼ぶため、[APIの互換性と廃止](versioning.md)の後方互換の判定に従う。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
