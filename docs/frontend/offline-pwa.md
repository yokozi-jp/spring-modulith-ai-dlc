---
type: Convention
title: オフライン対応
description: オフライン対応を導入する条件と、service workerのcache戦略、データの保存先、ロジックの置き場所、オフラインの判定と切替、認証、テスト、ログ、デプロイの方針を定める規約。オフラインで使える機能やPWAを検討するときに読む。
tags: [convention, frontend, pwa, offline, future-arch-guidelines]
---

# オフライン対応

オフライン対応は要件がある場合だけ導入し、導入時はservice workerのcache戦略をNetwork-first、データの保存先をIndexedDBにする。
オフラインの判定は実際のrequestの失敗で行い、オンラインとオフラインを自動で切り替える。
service workerとstorageの基盤は独自に実装せず、ライブラリを使う。

## 導入の判断

- 現在はオフライン対応を要件にしていないため、service workerとweb app manifestを追加しない。
- 導入する場合は、追加する依存とCSPの変更をADRで判断する。CSPのdirectiveは[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)に従い、必要になったものだけを追加する。
- PWAは、OSやhardwareに深く依存する機能、app storeでの配布、nativeと同等のUIを提供できない。これらが要件にある場合はモバイルアプリを検討する。
- オフライン対応ではデータ構造と業務ロジックをサーバーとクライアントに二重に持ちやすいため、変更を両方へ反映する手順を導入時に決める。
- service workerは全通信のproxyになり得るため、作業量と影響範囲をフロントエンド全体として見積もる。

## cache戦略と保存先

- オフラインで表示する静的ファイルはservice workerのcacheで扱い、通常のアプリケーションではNetwork-firstを使う。
- 最新データが不要で静的コンテンツが中心のページでは、Cache-first、Stale-while-revalidate、Cache-then-networkを使ってよい。
- Cache-onlyとNetwork-onlyはオフライン化に使わない。
- オフラインで使うデータは最初からIndexedDBに保存する。localStorageは同期APIであり、service workerから使えないためである。

## ロジックの置き場所

- 新規に構築する場合は、オフライン時のロジックを通常のクライアントのコードに置く。
- 既存のアプリケーションの一部にオフライン機能を加える場合に限り、service workerでAPIの代わりの処理を実装してよい。
- service workerとstorageを直接扱わず、Viteではvite-plugin-pwa、それ以外ではWorkboxを使う。
- installしてstandaloneで起動できるよう、web app manifestを置く。利用者の手順を訓練しにくい一般向けのサービスとSaaSでは必ず置く。

## オフラインの判定と切替

- `navigator.onLine` でオフラインを判定しない。
- 実際のAPI requestのnetwork errorでオフラインを判定する。APIを呼ばない時点で判定する場合は、health checkのendpointを使う。
- 判定した後のrequestでも、network errorを扱う。
- オフラインを検出したら自動でオフライン用の処理へ切り替え、オンラインに戻ったときに同期する。利用者に手動でモードを切り替えさせない。

## 認証

- オフライン時に使う利用者情報は、サーバーから取得した表示用の情報だけをstorageに保存し、loginのたびに更新する。
- tokenは保存しない（[ADR-007](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)）。
- 同期するrequestでも、サーバーはrequestに含まれる利用者情報と認証済みの利用者が一致することを検証する。

## テスト、ログ、デプロイ

- 通常のテストの方針で自動テストを書き、オフラインの判定、切替、service workerの動作は定期的に手動でも確認する。
- service workerのversionを一意に特定できる値（build時のGit commit hashなど）をログに出す。
- オフライン中のログには処理の起点で生成したtrace IDを含め、操作したstorageのデータにも同じIDを保存する。
- オフライン中のログは、オンラインに戻ったときに監視基盤へ送る。
- 静的ファイルへ複数の経路（CDN経由とload balancer経由など）で到達できる場合は、配信の構成を変えるたびに全経路で動作を確認する。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
