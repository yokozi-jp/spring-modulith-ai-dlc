---
type: Runbook
title: フロントエンドに利用者向け機能を追加する
description: 新しい利用者向け機能を追加するときに、業務機能名、route、server state、OpenAPI operation、API client生成、状態、テストの配置を決める順序を示し、フロントエンドに新しい画面や業務機能を追加するときに読む。
tags: [runbook, frontend, feature]
---

# フロントエンドに利用者向け機能を追加する

業務機能名を決めてから`features`と`routes`を作り、server state、API client、状態、テストの順に配置を決める。
必要なAPIが無いときやbackendの変更が要るときは、回避せずに確認を取る。
各手順の規約は[フロントエンドアーキテクチャ](architecture.md)、[ルーティングと状態管理](routing-and-state.md)、[OrvalとAPI境界](api-client-orval.md)、[UIとスタイル](ui-and-style.md)、[国際化](i18n.md)、[テストと検証](testing.md)にある。

## 機能追加時の確認

新しい利用者向け機能を追加するときは、次の順序で配置を決める。

1. 利用者が達成する目的と業務機能名を決める。
2. 対応するバックエンド業務モジュールがある場合は同じ業務語彙を使う。
3. `features/<business-feature>`を作り、最初のpageまたはcomponentをkebab-caseのファイル名（`<feature>-page.tsx`）で置く。
4. URLとsearch parameterを[routeの形](routing-and-state.md#routeの形)に従って`routes`に定義し、routeからfeatureのpageを接続する。
5. 初期描画に必要なserver stateがある場合は、TanStack Queryのquery optionsをloaderからpreloadする。
6. OpenAPI operationへ所有featureのtagと安定した`operationId`を付ける。
7. Orvalの再生成は[APIを変更する](../web-api/runbook-api-change.md)の手順で`task api-gen`を実行する。
8. local UI state、server state、URL state、form stateを対応する既存機構へ割り当てる。
9. 利用者向けの固定文言を追加または変更する場合は、[国際化](i18n.md)に従い、`src/i18n/locales/`の`ja.json`と`en.json`を同じ変更で更新する。
10. custom Hookと共通化は、具体的な再利用または外部system同期が存在する場合だけ追加する。
11. 対象コードの隣へ最小のtestを追加し、[Lintとテストのリファレンス](../tooling/lint-and-test.md#フロントエンド)に従って`task fe-verify`を実行する。

## APIやbackendの変更が要るときの確認

必要なAPIがOpenAPIに無いときは、手書きのfetchや仮実装で回避せず、何が足りないかを示して確認を取る。
回避した実装はbackendとの契約から外れ、APIを追加したときに作り直しになるためである。

backendの変更が要るときは、変更内容を示して確認を取ってから進める。
API契約の変更は他の画面やbackendの所有者にも影響するためである。
