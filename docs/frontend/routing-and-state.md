---
type: Convention
title: フロントエンドのルーティングと状態管理
description: TanStack Router の route file の責務と形、認証が要る画面の layout、loader による server state の preload、状態表示の分担、mutation の後の cache の無効化、状態の置き場所、custom Hook を作る条件と置き場所と名前、Effect の禁止と代わりの書き方を定める。route、loader、mutation、state、Hook、Effect を追加または変更するときに読む。
tags: [convention, frontend, routing, state, react]
---

# フロントエンドのルーティングと状態管理

route file は URL と画面の接続だけを担当し、画面の実装は `features` に置く。
初期描画に必要な server state は loader から TanStack Query へ preload する。
状態は意味に対応する既存機構（React Hooks、TanStack Query、TanStack Router、TanStack Form）へ置き、global store を先に追加しない。
feature の custom Hook は具体的な用途名で表せる場合に作り、`features/<feature>/hooks/` にだけ書く。Effect は使わない（ADR-088）。

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
    ├── route.tsx          # アプリシェル
    ├── tenants.tsx        # /tenants（テナントを選ぶ画面）
    └── _tenant/
        ├── route.tsx      # 選んだテナントを確かめるbeforeLoad
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

テナントを選んだ後だけ出す業務の画面は、pathless layoutの `_authenticated/_tenant` の下に置く。
`_tenant/route.tsx` の `beforeLoad` が選んだテナント（`GET /api/v1/current-tenant`）を読み、typeが `/problems/tenant-not-selected` の403なら `/tenants` へ移す。
テナントを選ぶ画面の `_authenticated/tenants.tsx` は、ログインが要り、テナントが要らないため、`_tenant` の外に置く。
テナントを選んだ後は、routerで遷移せず、文書を `/` で読み直す。
選ぶ前に読んだ状態を持ち越さず、focusを文書の先頭に戻すためである。
業務の画面のloaderが同じ403を受けたときは、`RouteError` がテナントを選ぶ画面へのリンクを出す（[APIの認証、セッション、権限](../web-api/authentication-and-session.md#テナントを選ぶ)）。

## 状態表示の分担

初期描画のdataは、loaderで生成された `get<Operation>SuspenseQueryOptions()` を `preloadQuery`（`src/api/preload-query.ts`）でcacheに入れ、componentは同じoptionsを `useSuspenseQuery` で読む。
`preloadQuery` は、TanStack Queryが `ensureQueryData` の後継とする `queryClient.query()` を呼び、cacheにdataがあれば取得しない。
ただし、mutationの後に無効化されたqueryは取得し直すので、更新の後に遷移した画面は古い値を描画せずにpendingを表示する。
`ensureQueryData` は非推奨（Oxlintの `typescript/no-deprecated` が検出する）であり、suspense用のoptionsは `queryFn` を省略可能とする型のため `exactOptionalPropertyTypes` の下で `query` へそのまま渡せないので、loaderから直接呼ばない。

状態ごとの表示は次のように分担する。

- **Loading**：routerの `defaultPendingComponent`（`components/route-pending.tsx`）が表示する。
- **Error**：routerの `defaultErrorComponent`（`components/route-error.tsx`）が表示する。
- **存在しないresource**：loaderで `notFound()` を投げ、routerの `defaultNotFoundComponent`（`components/route-not-found.tsx`）が表示する。
- **Empty**と**Content**：画面のcomponentが分ける。

例外は、画面の主な内容とは別の補足の情報（別のモジュールが持つ状態など）を出す欄で、その欄の取得に失敗しても画面の残りの表示と操作を残す必要があるときだけにする。
その欄は、loaderでpreloadせず、欄のcomponentで生成されたsuspenseなしの `use<Operation>` で読み、LoadingとErrorを欄の中で出す。
欄の失敗は `RouteError` にならず、画面の残りはそのまま使える。
画面の主な内容と、取得できなければ画面が意味をなさないdataには、この例外を使わない。

既定値は `src/router-defaults.ts` の `routerDefaults` にまとめ、`main.tsx` とrouteのテストが同じobjectを `createRouter` に渡す。

errorの表示はcatalogの文言を使い、`error.message` を画面に出さない。
`isTenantNotSelected` でない403は、`RouteError` が権限がないことを伝える画面とトップへのリンクを出し、再試行は出さない。
再試行しても結果が変わらず、4xxは自動の再試行もしないためである。
必要な役割は文言に書かない。役割の構成が変わっても文言を直さずに済むようにするためである。

再試行は、`useQueryErrorResetBoundary` の `reset()` でqueryのerrorを戻してから `router.invalidate()` を呼ぶ。

401は `QueryCache` と `MutationCache` の `onError` がページ全体を遷移させる（`@frontend/api/unauthorized-redirect` の `createUnauthorizedRedirect()`）。
同じ文書でqueryかmutationが1回でも成功した後の401は `/session-expired` へ移り、それ以外の401（ブックマークや最初の訪問）はログインへ移る。
サーバーは有効期限切れと未認証を区別せず（業務の `type` を持つのは422と409だけである。[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）、区別できても次の訪問は同じ判定を受けるので、遷移先はその文書で成功を見たかを知るSPAが決める。
成功は `QueryCache` と `MutationCache` の `onSuccess` が `QueryClient` ごとのclosureに記録し、記録は文書が続く間だけ残る。
ログインへの遷移で文書を捨てるので、ログインの後の文書は成功の記録なしで始まる。
`/session-expired` は `_authenticated` の外の画面でAPIを呼ばず、「もう一度ログイン」のリンクでログインへ移る。
このため、成功の後の401は、バックグラウンドの再取得で受けたものも含めて、KeycloakのSSOセッションが残っていても、この画面で「もう一度ログイン」を1回押すまでログインし直さない。
入力中だった値は残らない。
どちらへ移るときも、遷移の前に、いまのpath、query、hashを戻り先としてsessionStorageに1つだけ保存する（`@frontend/api/login-return-to`）。
`/session-expired` は戻り先を消さない。
`/` のrouteの `beforeLoad` が戻り先を1回読んで消し、`new URL()` で解決した先が同一originのpathのときだけ、その画面へ移す。使えない値は `/` のまま表示する。
ログインの後に着く `/` と `/tenants` は保存しない。
戻り先はその tab の sessionStorage にあり、`/` を開くか `/logged-out` を開くまで残る。別のテナントを選んだときは、その画面が存在しないことがある。
テナントを複数持つ利用者は、テナントを選んだ後に文書を `/` で読み直すので、選んだ後の `/` が戻り先を読む。
ログインへの遷移は、30秒以内の2回目は遷移せず、戻り先も書き換えず、queryは `RouteError`、mutationは呼び出したcomponentのerror状態に任せる。
`/session-expired` への遷移には30秒の抑止を掛けない。
この画面はAPIを呼ばず、次の文書は成功の記録なしで始まるためである。
同じ文書で401が続くと `location.assign` を重ねて呼ぶが、最後の遷移が残るだけで害はない。

4xxの `ApiProblemError` は再試行しない。
ただし、408と429は再試行してよい応答なので（RFC 9110 §15.5.9、RFC 6585 §4）、5xxと同じく再試行する。
loaderの `preloadQuery` も `defaultOptions.queries.retry` に従い、5xx、408、429と通信の失敗は最大3回再試行してから `RouteError` になる。

mutationは `defaultOptions.mutations.retry`（`retryIdempotentMutation`）に従い、同じ変数で `mutationFn` を呼び直す。
変数の `headers` の `Idempotency-Key` も同じになる。再試行する失敗と回数は[再試行への備え](../web-api/http-methods.md#再試行への備え)が定める。
`Idempotency-Key` を送らないmutation（テナントの選択）は、生成Hookの `mutation` の options に `retry: false` を渡す。

route固有の `pendingComponent` や `errorComponent` は、`react/no-multi-comp` があるためroute fileとは別のファイルに置く。

## mutationの後のcache

mutationの後のcacheは次のとおりに扱う。

- mutationのたびに、`createQueryClient()`（`src/api/query-client.ts`）の `MutationCache` の `onSettled` が全queryを無効化する。成功でも失敗でも無効化し、描画中のqueryの再取得（再試行を含む）が終わるまで、mutationは `isPending` のままになる。
- 無効化の範囲を絞るときは、mutationに `mutationKey` か `meta` を付けてglobalの `onSettled` で分けるか、Orvalの `mutationInvalidates` を使う。
- 個別に無効化するときは、生成された `get<Operation>QueryKey()` を使い、配列のkeyを手書きしない。OrvalのkeyはURLの文字列を先頭に持つので、手書きのkeyは一致しない。
- `router.invalidate()` は呼ばない。例外は、mutationの結果が `beforeLoad` やloaderの戻り値（`useLoaderData` で読む値）を変える場合である。
- 画面遷移と通知は `mutate` に渡すcallbackに置き、`useMutation` のoptionsやglobalのcallbackに置かない。`mutate` のcallbackは再取得が終わった後に呼ばれるので、最新のcacheを読める。
- 応答を `setQueryData` でcacheに書くときは、cacheの形（`{ data, status, headers }`）と型をqueryの型に合わせる。
- mutationの結果が非同期のListenerで後から決まるとき（確定の後の決済記録）は、無効化の再取得だけでは結果を拾えない。
  queryの `refetchInterval` を関数にし、結果がない間だけ読み直し、期間か回数の上限を名前付きの定数で置き、失敗したら止める。
  上限を過ぎたら読み直しのボタンを出し、状態の文言はlive regionに入れる（例は `payment-status.tsx`）。

409が返る画面の扱いは[更新の競合（409）の画面の扱い](update-conflicts.md)に従う。

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

featureのcustom Hookは、`useOrderStatusActions` や `useDraftOrderForm` のような具体的な用途名で表せる場合に作る。
stateful logicの再利用、外部systemとの同期、画面のmutation、form、通知の状態をcomponentから分けること、のどれでもよい。
呼び出し元が一つの画面だけでもよく、再利用の有無は判断しない。

一回の `useState` を隠すだけのHook、生成Hookをそのまま返すだけのwrapper Hook、`useMount` のようにReact lifecycleを言い換えるHookは作らない。

日付変換、金額計算、responseの整形など、React stateを使わない処理は通常のTypeScript関数にする。

Hookはcomponentまたは別のcustom Hookのトップレベルから呼び、条件分岐、loop、event handler、通常の関数から呼ばない。

React Compilerを有効にしているため、参照同一性が契約になる場合や計測で効果を確認した場合を除き、`useMemo` と `useCallback` を先回りして追加しない。

### Effectの禁止

`useEffect`、`useLayoutEffect`、`useInsertionEffect`、`useEffectEvent` は使わない（[ADR-088](../adr/ADR-088-ban-react-effects-in-frontend.md)）。
Oxlintの `react-rules/no-effect`（`frontend/lint/react-rules.js`）が、`react` からの名前付きimport、再export、default importの名前（`React.useEffect`）の参照、分割代入を検出し、`task fe-check` とCIで失敗にする。
自前の関数で包んだEffectは検出しないため、reviewで確認する。

Effectの代わりに、次の形で書く。

- **画面のtitle**：componentの中に `<title>` を描画する。
- **そのcomponentが描画する要素に結び付く処理**：ref callbackを使い、後始末はcleanup関数で返す。
  ref callbackは関数の同一性が変わるたびに再実行されるため、本体は何度呼んでも同じ結果になる形にする。
  React Compilerが関数を安定させる前提なので、`useMemo` や `useCallback` を足さない。
- **要素に結び付かない外部のstoreの購読**：`useSyncExternalStore` を使う。
- **propsやstateから計算できる値**：stateで持たず、render中に計算する。
- **利用者の操作に応じた処理**：送信や通知は、その操作のevent handlerで行う。
- **propが変わったときのstateの初期化**：stateをすべて戻すときは親から `key` を渡してcomponentを作り直し、一部だけを調整するときはrender中に計算する。
- **route全体の例外の報告**：routerの `defaultOnCatch` に置く（`router-defaults.ts`）。
- **server stateの取得**：[ルーティング](#ルーティング)のとおり、loaderとTanStack Queryで取得する。

ref callbackは、Effectに向けた `react/set-state-in-effect` などの検査が効かない。
ref callbackの中でstateを更新したり、serverへ取得したりしない。

どうしてもEffectが要る場面（第三者のwidgetの初期化など）は、行単位の `oxlint-disable` に理由を書く。
その場面が繰り返し生じるなら、ADR-088を見直す。

### Hookの名前とファイル

`use` で始まる名前は、Hookを呼ぶ関数だけに付ける。

Hookを呼ばない関数に `use` を付けると、Rules of Hooksの制約が呼び出し側にかかり、条件分岐の中から呼べなくなるためである。

Hookを呼ぶのに `use` で始まらない関数は、Oxlintの `react/rules-of-hooks` が検出する。

Hookを呼ばない関数に `use` を付ける形はOxlintが検出しないため、reviewで確認する。

Hookのファイル名は `use-media-query.ts` のようにHook名をkebab-caseにし、Oxlintの `unicorn/filename-case` が検査する。

置き場所は[目標のディレクトリ構成](architecture.md#目標のディレクトリ構成)のとおり、custom Hookがあるfeatureの `hooks/` とする。

featureのcustom Hookの定義（`use` で始まる関数宣言）は、featureの `hooks/` にだけ書く。
複数のfeatureが使うHookは今は存在せず、必要になったときに置き場所を決める。

Reactのstateを使わない処理は、Hookにせずfeatureのmoduleの関数にする（[feature の module](file-roles/feature-module.md)）。
この配置を守る検査は[フロントエンドのLint設定](lint-config.md#ファイルの範囲ごとの制限)にある。

custom Hookのテストは[テスト観点の割り当て](test-strategy.md#観点ごとのテスト)に従う。

## 関連資料

- [フロントエンドアーキテクチャ](architecture.md)
- [OrvalとAPI境界](api-client-orval.md)
- [フロントエンドのテストと検証](testing.md)
- [ADR-023: TanStack Form と Zod を採用する](../adr/ADR-023-adopt-tanstack-form-and-zod.md)
- [ADR-026: OXCでReact Compilerを有効化する](../adr/ADR-026-enable-react-compiler-with-oxc.md)
- [ADR-032: Frontendを業務機能単位で構成する](../adr/ADR-032-organize-frontend-by-business-feature.md)
- [ADR-088: フロントエンドで useEffect を禁止し、Oxlint で止める](../adr/ADR-088-ban-react-effects-in-frontend.md)
- [React: Reusing Logic with Custom Hooks](https://react.dev/learn/reusing-logic-with-custom-hooks)
- [React: Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks)
- [React: You Might Not Need an Effect](https://react.dev/learn/you-might-not-need-an-effect)
- [React: Synchronizing with Effects](https://react.dev/learn/synchronizing-with-effects)
- [React: Separating Events from Effects](https://react.dev/learn/separating-events-from-effects)
- [TanStack Router: External Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)
- [TanStack Router: Authenticated Routes](https://tanstack.com/router/latest/docs/framework/react/guide/authenticated-routes)
- [TanStack Router: Not Found Errors](https://tanstack.com/router/latest/docs/framework/react/guide/not-found-errors)
- [TanStack Query: Prefer the use of queryOptions](https://tanstack.com/query/latest/docs/eslint/prefer-query-options)
