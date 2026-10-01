---
type: Convention
title: 画面の認可制御
description: SPAで行う認可制御の役割、制御の対象と置き場所、権限情報の取得元を定める規約。権限によって画面やcomponentの表示を変えるとき、権限が必要なrouteを追加するときに読む。
tags: [convention, frontend, authorization, routing, future-arch-guidelines]
---

# 画面の認可制御

SPAの認可制御は利用者の誤操作を防ぐためのものであり、保護はバックエンドの認可が担う。
認可は画面単位でrouteに集約し、同じ画面の中でcomponentを出し分ける制御を最小にする。
権限情報はバックエンドのAPIから取得し、ロールと権限の対応をSPAに持たない。

## 役割

- SPAの認可制御は、利用者が権限のない操作を誤って試みないようにするために行う。
- SPAのコードは利用者が改変できるため、SPAの認可制御を安全性の根拠にしない。
- すべてのAPIはバックエンドで認可する。SPAで画面を隠しても、その画面が使うAPIをバックエンドが認可していなければ保護にならない。

## 制御の対象と置き場所

- SPAで制御するのは、画面へのアクセスと、画面の中のcomponentの表示である。
- 画面へのアクセスは、TanStack Routerの共通の親routeの `beforeLoad` にまとめて判定し、画面ごとに分散させない。
- 権限によって表示が大きく変わる場合は、同じ画面の中で出し分けず、画面を分ける。

## 権限情報の取得

- 認証はサーバー側のsessionで行い、SPAはtokenを持たないため（[ADR-007](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)）、利用者の権限情報をバックエンドのAPIから取得する。
- 権限情報はserver stateとしてTanStack Queryで扱う（[フロントエンドのルーティングと状態管理](routing-and-state.md)）。
- ロールと権限の対応はバックエンドで管理し、SPAはAPIが返す権限を、routeやcomponentが必要とする権限と照合する。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
