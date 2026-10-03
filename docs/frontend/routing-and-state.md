---
type: Convention
title: フロントエンドのルーティングと状態管理
description: TanStack Router の route file の責務と形、認証が要る画面の layout、loader による server state の preload、状態表示の分担、状態の置き場所、custom Hook を作る条件を定める。route、loader、state、Hook を追加または変更するときに読む。
tags: [convention, frontend, routing, state, react]
---

# フロントエンドのルーティングと状態管理

route file は URL と画面の接続だけを担当し、画面の実装は `features` に置く。
初期描画に必要な server state は loader から TanStack Query へ preload する。
状態は意味に対応する既存機構（React Hooks、TanStack Query、TanStack Router、TanStack Form）へ置き、global store を先に追加しない。
custom Hook は具体的な用途名で表せる再利用または外部 system との同期がある場合だけ作る。

## ルーティング

`routes` はTanStack Routerのfile-based routingを定義する技術境界である。

route fileはpath、path parameter、search parameter、loader、error境界、画面componentの接続を担当する。

画面の表示ロジックと利用者操作は `features/<business-feature>` へ置き、route fileをpage実装の置き場にしない。

初期描画に必要なserver stateはloaderからTanStack Queryへpreloadし、componentのmount後に同じ取得を開始してwaterfallを作らない。

Orvalが生成したquery optionsだけで要件を表せる場合は、それをloaderとcomponentの両方から利用する。

別のtagの生成query optionsは、他のfeatureから直接使ってよく、変換は使う側の `select` で書く。

複数queryの合成、業務上の既定値、追加のselect処理が必要な場合だけ、feature内の `queries.ts` または用途を表すcustom Hookへ閉じ込める。

routeの近くへテストを置く場合は、TanStack Routerのroute候補に含めないよう、現在の規約どおりファイル名を `-` で始める。

## routeの形

routeはdirectory形式で書く。

一覧、新規、詳細、編集は `index.tsx` と名前付きのrouteで置き、親のlayout routeを作らない。

``` text
src/routes/
├── __root.tsx
└── _authenticated/
    ├── route.tsx          # beforeLoadとアプリシェル
    └── orders/
        ├── index.tsx      # /orders
        ├── new.tsx        # /orders/new
        └── $orderId/
            ├── index.tsx  # /orders/$orderId
            └── edit.tsx   # /orders/$orderId/edit
```

`route.tsx` は、子が共有する `beforeLoad`、`loader`、`validateSearch`、layoutがあるときだけ作る。

`_` 接尾辞は、親のlayoutを共有する子のうち一部だけを外す場面に限る。

この形はlintでは制限しない。

URLのpathとparameterの命名は[フロントエンドのURL設計](url-design.md)に従う。

## 認証が要る画面とアプリシェル

認証が要る画面はpathless layoutの `_authenticated` の下に置く。

`_authenticated/route.tsx` に、配下に共通する `beforeLoad`（[画面の認可制御](authorization-ui.md)）とアプリシェルを置く。

layout routeにcomponentを持たせるなら、そのcomponentで `<Outlet />` を描画する。

現在の `_authenticated/route.tsx` は `components/app-shell.tsx` を描画するだけであり、`beforeLoad` は最初の権限APIと同時に足す。

## 状態表示の分担

初期描画のdataはloaderで `ensureQueryData` し、componentは `useSuspenseQuery` で読む。

状態ごとの表示は次のように分担する。

- **Loading**：routerの `defaultPendingComponent`（`components/route-pending.tsx`）が表示する。
- **Error**：routerの `defaultErrorComponent`（`components/route-error.tsx`）が表示する。
- **存在しないresource**：loaderで `notFound()` を投げ、routerの `defaultNotFoundComponent`（`components/route-not-found.tsx`）が表示する。
- **Empty**と**Content**：画面のcomponentが分ける。

既定値は `src/router-defaults.ts` の `routerDefaults` にまとめ、`main.tsx` とrouteのテストが同じobjectを `createRouter` に渡す。

errorの表示はcatalogの文言を使い、`error.message` を画面に出さない。

再試行は、`useQueryErrorResetBoundary` の `reset()` でqueryのerrorを戻してから `router.invalidate()` を呼ぶ。

route固有の `pendingComponent` や `errorComponent` は、`react/no-multi-comp` があるためroute fileとは別のファイルに置く。

## React Hooksと状態

React componentはfunction componentとHooksで実装する。

HooksはReactのstate、context、effectなどをfunction componentから使うための標準機構であり、アプリケーション全体の層を表すものではない。

状態は、その意味に対応する既存機構へ置く。

- **Local UI state**：開閉、選択中のtab、一時的な入力などをReactの `useState` または `useReducer` で扱う。
- **Server state**：API response、loading、error、cache、再取得をTanStack Queryで扱う。
- **URL state**：共有可能な検索条件、page、sorting、filterをTanStack Routerのsearch parameterで扱う。
- **Form state**：入力値、検証、送信状態をTanStack Formで扱う。
- **Derived value**：既存stateから計算できる値はstateへ複製せず、render中または純粋関数で求める。

これらで扱えない共有stateが実在するまでは、ReduxやZustandなどのglobal storeを追加しない。

custom Hookは、再利用するstateful logicまたは外部systemとの同期を、`useMediaQuery` や `usePublicationFilters` のような具体的な用途名で表せる場合に作る。

一回の `useState` を隠すだけのHook、生成Hookをそのまま返すだけのwrapper Hook、`useMount` のようにReact lifecycleを言い換えるHookは作らない。

日付変換、金額計算、responseの整形など、React stateを使わない処理は通常のTypeScript関数にする。

Hookはcomponentまたは別のcustom Hookのトップレベルから呼び、条件分岐、loop、event handler、通常の関数から呼ばない。

React Compilerを有効にしているため、参照同一性が契約になる場合や計測で効果を確認した場合を除き、`useMemo` と `useCallback` を先回りして追加しない。

## 関連資料

- [フロントエンドアーキテクチャ](architecture.md)
- [OrvalとAPI境界](api-client-orval.md)
- [フロントエンドのテストと検証](testing.md)
- [ADR-023: TanStack Form と Zod を採用する](../adr/ADR-023-adopt-tanstack-form-and-zod.md)
- [ADR-026: OXCでReact Compilerを有効化する](../adr/ADR-026-enable-react-compiler-with-oxc.md)
- [ADR-032: Frontendを業務機能単位で構成する](../adr/ADR-032-organize-frontend-by-business-feature.md)
- [React: Reusing Logic with Custom Hooks](https://react.dev/learn/reusing-logic-with-custom-hooks)
- [React: Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks)
- [TanStack Router: External Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)
- [TanStack Router: Authenticated Routes](https://tanstack.com/router/latest/docs/framework/react/guide/authenticated-routes)
- [TanStack Router: Not Found Errors](https://tanstack.com/router/latest/docs/framework/react/guide/not-found-errors)
- [TanStack Query: Prefer the use of queryOptions](https://tanstack.com/query/latest/docs/eslint/prefer-query-options)
