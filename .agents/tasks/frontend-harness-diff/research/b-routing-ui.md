# グループ B の調査：ルーティング、UI、構成

対象は [report.md](../report.md) の C6、C8、C13、C15、C16 と、次のラウンドで使う C5 である。
各主張には一次情報への link を付けた。
「確認済み」は文書、ソース、実行結果のいずれかで確かめた事実を指し、「推測」はそれ以外を指す。

## 調査の前提

### 版と取得元

| 対象 | 版（`frontend/package.json` と `node_modules` で確認） | 読んだ文書 |
| --- | --- | --- |
| React | 19.3.0 | react.dev（版付きの文書が無いため main） |
| TanStack Router | 1.170.38（router-plugin 1.168.40） | `@tanstack/react-router@1.170.38` タグの `docs/router` と source |
| TanStack Query | 5.103.2 | `@tanstack/react-query@5.103.2` タグの docs |
| Base UI | 1.8.0 | `v1.8.0` タグの docs と source |
| shadcn | CLI 4.21.0 | GitHub の main（commit `295a1f1`）。CLI は registry を実行時に取得するので main を読んだ（推測。CLI の版と registry の対応は確かめていない） |
| Orval | 8.36.0 | `v8.36.0` タグの docs |
| TypeScript | 7.0.2 | 公式 handbook と 5.9.3 の source |
| Oxlint | vite-plus 0.3.3 に同梱 | oxc.rs の rule 文書と実行結果 |

公開サイトへの link は読みやすさのために `latest` や `v5` を使っているが、内容は上の版の文書で照合した。

### lint と型検査で確かめたこと

`frontend/` をリポジトリ外の一時ディレクトリに複製し、`node_modules` だけを参照して、試験用のファイルを足してから `vp lint` と `tsc --noEmit` を実行した。
リポジトリのファイルは変更していない。
複製した直後の `vp lint` はエラー 0 件だった。
結果は次のとおりである（すべて確認済み）。

| 試験 | 結果 |
| --- | --- |
| `src/features/order/OrderListItem.tsx` を置く | `unicorn(filename-case)` が kebab-case でないとしてエラー |
| `src/features/order/components/order-list-item.tsx` を置く | 違反なし |
| `orders_.$orderId_.edit.tsx`、`orders/$orderId/edit.tsx`、`orders/route.tsx`、`_app/route.tsx`、`orders.index.tsx`、`(app)/dashboard.tsx` を置く | filename-case の違反なし |
| import 文が 12 個のファイル | `import(max-dependencies)` が上限 10 としてエラー |
| 305 行のファイル | `eslint(max-lines)` がエラー |
| 1 ファイルに component を 2 個 | `react(no-multi-comp)` がエラー |
| route file に `component` 用と `pendingComponent` 用の関数を 2 個 | `react(no-multi-comp)` がエラー |
| `<Link>` の中に `<Button>` | 違反なし（検出する rule が無い） |
| `<Link className={buttonVariants(...)}>` | 違反なし |
| `components/ui/button.tsx` から `buttonVariants` も export する | `react(only-export-components)` がエラー |
| `<div role="status">` | `jsx-a11y(prefer-tag-over-role)` が `output` を使うよう求めてエラー |
| 同名の `const` と `type` を一つのファイルで宣言 | `eslint(no-redeclare)` がエラー |
| `satisfies Record<Status, ...>` で key を一つ欠く | TS2741 で型エラー |
| ``t(`orderStatus.${status}`)`` で catalog に key が一つ無い | TS2345 で型エラー |

route の親子関係は、`vp lint` が読み込む router plugin が生成した `routeTree.gen.ts` で確かめた。

## 要約

| トピック | 推奨案 |
| --- | --- |
| C6 | 一覧、新規、詳細、編集は `index.tsx` を使う route で書き、`_` 接尾辞を標準にしない。共通の `beforeLoad` やアプリシェルは pathless layout の `route.tsx` に置き、component を持たせるなら `<Outlet />` を描画する |
| C8 | Loading と Error は loader の preload と route の境界（router の既定 component）で受け、画面の component は Empty と Content だけを分ける。primitive は shadcn の `Skeleton`、`Empty`、`AlertDialog` を `components/ui` に入れ、router 既定の状態表示とアプリシェルの置き場所を新たに決める |
| C13 | `<Link className={buttonVariants(...)}>` で書く。`Button` の `render` に link を渡す形は、Base UI が link の role を `button` で上書きするので使わない。`buttonVariants` の公開方法を決める必要がある |
| C15 | 区分値は現 `response-body.md` のとおり frontend の定数と message catalog で持つ。網羅は生成型の union と `satisfies Record<...>`、型付き catalog で検査し、色は Badge の variant か semantic token で表す |
| C16 | 数値の上限は lint（import 10、300 行、1 ファイル 1 component）に任せる。docs には、責務の境界で分けることと、状態表示を route の境界へ出すと分割が減ることだけを書く |
| C5 | kebab-case に統一する。現 lint はすでに kebab-case を強制しており、`component-design.md` と ADR-032 の PascalCase の記述と食い違っている |

## C6 route の命名と画面の分け方

### 標準

Web の仕様に route file の規範は無く、TanStack Router の file-based routing の規約が規範に当たる。
[File Naming Conventions](https://tanstack.com/router/latest/docs/framework/react/routing/file-naming-conventions) は次を定める（確認済み）。

- `.` は入れ子を表し、`blog.post` は `blog` の子になる。
- `_` で始まる segment は pathless layout route であり、子 route を URL と照合するときに使われない。
- `_` で終わる segment は、その route を親 route の入れ子から外す。
- `index` token は、URL が親 route と完全に一致したときに照合される。
- directory で整理するときは、`route.tsx` がその directory の path の route file になる。
- `-` で始まるファイルと directory は route tree から除外される。
- `(folder)` は route group であり、URL に含まれない。

[Outlets](https://tanstack.com/router/latest/docs/framework/react/guide/outlets) は、`<Outlet />` が一致した子 route を描画すること、route の `component` を省くと `<Outlet />` が自動で描画されることを述べる（確認済み）。

### ベストプラクティス

公式文書は file-based routing を推奨し、flat 形式と directory 形式の混在を認める（[Route Trees](https://tanstack.com/router/latest/docs/framework/react/routing/route-trees)）。
同じ文書の例は、親の `posts.tsx` を置かずに `posts/index.tsx` と `posts/$postId.tsx` を並べており、親の route file を作らない構成を正当な形として示している（確認済み）。

親の route file が無いと、生成される route はすべて root の直下に並ぶ。
一時コピーで `orders/index.tsx`、`orders/new.tsx`、`orders/$orderId/index.tsx`、`orders/$orderId/edit.tsx` を置くと、4 route とも親が root になった（確認済み）。
flat 形式の `orders.index.tsx`、`orders.$orderId.index.tsx`、`orders.$orderId.edit.tsx` でも同じだった。
`orders/route.tsx` を足すと、4 route の親がそれに切り替わった。

layout route の用途として、公式文書は子を layout component で包むこと、子の表示前に loader を要求すること、search parameter の検証、子への error と pending の fallback、共有 context を挙げる（[Layout Routes](https://tanstack.com/router/latest/docs/framework/react/routing/routing-concepts#layout-routes)）。
つまり `route.tsx` を置く理由は、子 route が共有する処理か画面があることである。
pathless layout は URL を変えずに子を包む（[Pathless Layout Routes](https://tanstack.com/router/latest/docs/framework/react/routing/routing-concepts#pathless-layout-routes)）。
[Authenticated Routes](https://tanstack.com/router/latest/docs/framework/react/guide/authenticated-routes) は、`_authenticated.tsx` の `beforeLoad` で配下の route をまとめて保護する例を示す（確認済み）。

`_` 接尾辞による non-nested route は、親の component tree を共有しない子を入れ子から外す仕組みとして説明されている（[Non-Nested Routes](https://tanstack.com/router/latest/docs/framework/react/routing/routing-concepts#non-nested-routes)）。
公式の例 `posts_.$postId.edit.tsx` は、`posts.tsx` と `posts.$postId.tsx` が master-detail として入れ子になっている前提で、編集画面だけを外す場面である。

### アンチパターン

- layout route に `component` を書き、その中で `<Outlet />` を描画しない。
  子 route は URL に一致しても画面に出ない。
  旧リポジトリが記録した不具合はこれに当たり、`component` を省いた layout route では起きない（Outlets の記述から、確認済み）。
- 一覧画面を `orders.tsx` の component に書き、その下に `orders.$orderId.tsx` を `_` 無しで作る。
  一覧の component が layout route になり、上と同じ不具合になる。
- `_` 接尾辞を全 route に付けて回避する。
  動作はするが、`_` を付け忘れた route が一つ混ざると上の不具合を再現し、親子関係がファイル名の一文字で決まるためレビューで見落としやすい（推測）。
- route file に page の実装を書く。
  `routing-and-state.md` が禁じている。
  加えて現 lint の `react/no-multi-comp` は、一つの route file に `component` 用と `pendingComponent` 用の関数を並べると失敗させる（確認済み）。

### デファクトスタンダード

TanStack Router 1.170.38 の公式 example は次の形をとる（確認済み）。

- [`examples/react/basic-file-based/src/routes`](https://github.com/TanStack/router/tree/main/examples/react/basic-file-based/src/routes)：`posts.route.tsx`（layout）、`posts.index.tsx`、`posts.$postId.tsx`。
- [`examples/react/start-basic/src/routes`](https://github.com/TanStack/router/tree/main/examples/react/start-basic/src/routes)：`posts.tsx`、`posts.index.tsx`、`posts.$postId.tsx`、`posts_.$postId.deep.tsx`。
  `_` は一つの route だけを入れ子から外す場面に使っている。
- [`examples/react/kitchen-sink-react-query-file-based/src/routes`](https://github.com/TanStack/router/tree/main/examples/react/kitchen-sink-react-query-file-based/src/routes)：`_auth.tsx` と `_auth.profile.tsx`（認証の pathless layout）、`dashboard.route.tsx`、`dashboard.invoices.route.tsx`、`dashboard.invoices.index.tsx`、`dashboard.invoices.$invoiceId.tsx`、`expensive/-components/Expensive.tsx`。

どの example も一覧を `index` route に置き、layout を `route` token か component 付きの親ファイルに置いている。
`_` 接尾辞は例外として使われている。

### 現リポジトリへの当てはめ

**既存の docs と ADR との整合**：`url-design.md` は `/orders`、`/orders/new`、`/orders/{orderId}`、`/orders/{orderId}/edit` の形を定めるが、route file の命名は決めていない。
`authorization-ui.md` は共通の親 route の `beforeLoad` で認可すると定めており、pathless layout がその受け皿になる。
`routing-and-state.md` は route file に page の実装を置かず、route の近くのテストを `-` で始めると定めている。
現 `src/routes/__root.tsx` は `component` を持たないので、`<Outlet />` が自動で描画されている（確認済み）。

**旧リポジトリとの比較**：旧は `products.tsx` を一覧にし、`products_.$id.tsx`、`products_.$id_.edit.tsx`、`products_.new.tsx` で入れ子を外していた。
動作は正しいが、`_` が必要になった原因は、一覧を layout route の component に書いたことにある。
一覧を index route に置けば `_` は要らず、付け忘れによる不具合が起きる形そのものが無くなる。

**推奨案**：

```text
src/routes/
├── __root.tsx
└── _authenticated/
    ├── route.tsx          # beforeLoad とアプリシェル。component を持つなら <Outlet /> を描画する
    └── orders/
        ├── index.tsx      # /orders
        ├── new.tsx        # /orders/new
        └── $orderId/
            ├── index.tsx  # /orders/$orderId
            └── edit.tsx   # /orders/$orderId/edit
```

- 一覧、新規、詳細、編集を別画面にするときは `index.tsx` を使い、親の `route.tsx` を作らない。
- `route.tsx` は、子が共有する `beforeLoad`、`loader`、`validateSearch`、layout があるときだけ作る。
  component を書くなら `<Outlet />` を描画する。
- `_` 接尾辞は、親の layout を共有する子のうち一部だけを外す場面に限る。
- 追記先は `routing-and-state.md` の「ルーティング」節であり、上の 3 行と短い例で足りる。

**利用者が決める論点**：

1. directory 形式（`orders/$orderId/edit.tsx`）と flat 形式（`orders.$orderId.edit.tsx`）のどちらを標準にするか。
   生成結果は同じである。
   directory 形式は route が増えても一覧しやすく、flat 形式はファイルが少ない段階で見通しがよい。
2. 認証の pathless layout の名前（`_authenticated`、`_app` など）と、アプリシェルをそこに置くか `__root.tsx` に置くか。
   ログイン前に表示する画面が SPA にあるかで決まる。
   `url-design.md` はログインを SPA の route にしないと定めている。
3. `_` 接尾辞を lint で制限するか。
   違反例がまだ無いので、docs だけにする方が `architecture.md` の「実在する違反例を基に lint rule を追加する」方針に合う。

## C8 状態表示とアプリシェル

### 標準

HTML、ARIA、WCAG は次を定める。

- `role="status"` は、警告にするほど重要ではない助言的な情報の live region であり、暗黙に `aria-live="polite"` と `aria-atomic="true"` を持つ（[WAI-ARIA 1.2 の status](https://www.w3.org/TR/wai-aria-1.2/#status)）。
- `role="alert"` は、重要で即時性のある情報の assertive な live region である（[alert](https://www.w3.org/TR/wai-aria-1.2/#alert)）。
- `aria-busy="true"` は要素が変更中であることを示し、支援技術は変更の完了を待ってよい。
  仕様は待機を MAY としており、義務づけていない（[aria-busy](https://www.w3.org/TR/wai-aria-1.2/#aria-busy)）。
- WCAG 2.2 の達成基準 4.1.3（Level AA）は、focus を受けない status message を role や property で支援技術に伝えられるようにすることを求める（[Understanding 4.1.3](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)）。
- `output` 要素の暗黙の role は `status` である（[ARIA in HTML](https://www.w3.org/TR/html-aria/#el-output)）。
  一方で HTML は `output` を、application の計算結果か利用者操作の結果を表す要素と定義している（[output 要素](https://html.spec.whatwg.org/multipage/form-elements.html#the-output-element)）。
- WAI-ARIA APG の modal dialog は、見える title を `aria-labelledby` で参照するか、`aria-label` を持つことを求める（[Dialog (Modal) Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/)）。
- 操作の確認 prompt は alert dialog の例であり、title に加えて `aria-describedby` で本文を参照する（[Alert and Message Dialogs Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/alertdialog/)）。

React と TanStack の規範は次のとおりである。

- `<Suspense>` は子が suspend する間 `fallback` を表示し、Effect や event handler の中の取得は検出しない（[Suspense の Caveats](https://react.dev/reference/react/Suspense#caveats)）。
- Error Boundary は描画中の error を捕まえて fallback を表示する。
  現時点で function component では書けない（[Catching rendering errors with an Error Boundary](https://react.dev/reference/react/Component#catching-rendering-errors-with-an-error-boundary)）。
- TanStack Router は loader の実行中に `pendingComponent` を、失敗時に `errorComponent` を描画する。
  route に無ければ親 route、さらに router の既定値をたどる（[The route loading lifecycle](https://tanstack.com/router/latest/docs/framework/react/guide/data-loading#the-route-loading-lifecycle)）。
- `pendingComponent` は loader が `pendingMs`（既定 1000 ms）を超えたときに表示され、一度表示すると `pendingMinMs`（既定 500 ms）は表示を続ける（同文書の「Showing a pending component」と「Avoiding Pending Component Flash」、[RouterOptions](https://tanstack.com/router/latest/docs/framework/react/api/router/RouterOptionsType)）。
- router option の `defaultPendingComponent`、`defaultErrorComponent`、`defaultNotFoundComponent` で、app 全体の既定を一箇所に置ける（同 RouterOptions、確認済み）。
- `defaultErrorComponent` を設定しないと router 同梱の `ErrorComponent` が使われる。
  これは英語の固定文言を表示し、利用者が error の内容を展開できる（[CatchBoundary.tsx](https://github.com/TanStack/router/blob/main/packages/react-router/src/CatchBoundary.tsx)、1.170.38 で確認済み）。
- TanStack Query の `useSuspenseQuery` では status と error の分岐が要らなくなり、`data` は必ず定義される。
  loading と error は Suspense と Error Boundary が受け持つ（[Suspense](https://tanstack.com/query/v5/docs/framework/react/guides/suspense)）。
- 同文書によれば、既定の `throwOnError` は cache に data が無いときだけ error を投げる。
  背景の再取得が失敗しても古い data を表示し続ける。
- Error Boundary から再試行するときは、`QueryErrorResetBoundary` か `useQueryErrorResetBoundary` で query の error を reset する（同文書）。
  TanStack Router の文書は、`errorComponent` の mount 時に reset し、再試行で `router.invalidate()` を呼ぶ例を示す（[Error handling with TanStack Query](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading#error-handling-with-tanstack-query)）。

### ベストプラクティス

**取得と状態表示の分担**：TanStack Router は、描画に必要な data を loader で preload すると、loading の一瞬の表示と component 起点の waterfall を避けられるとする（[External Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)）。
loader が短時間で終わるなら placeholder を出さず、次の route の準備が整うまで suspense に任せるのが理想だとも述べる（[Handling Slow Loaders](https://tanstack.com/router/latest/docs/framework/react/guide/data-loading#handling-slow-loaders)）。
この形では Loading と Error を route の境界が受け持ち、画面の component は `useSuspenseQuery` で data を読み、Empty と Content だけを分ける。

**`useQuery` を使う場合の順序**：TanStack Query の Queries guide は、pending、error の順に確認してから成功時の表示に進む例を示す（[Queries](https://tanstack.com/query/v5/docs/framework/react/guides/queries)）。
保守者の TkDodo は、背景の再取得が失敗すると error と古い data が同時に存在するため、pending と error を先に見ると表示中の data が error 表示に置き換わると指摘し、data があればそれを優先する順序を勧める（[Status Checks in React Query](https://tkdodo.eu/blog/status-checks-in-react-query)）。

**Skeleton と spinner の使い分け**：NN/g は、1 秒未満の load には skeleton も spinner も要らず、10 秒未満の全画面の load には skeleton、単一の module には spinner、10 秒を超える load には progress bar を勧める（[Skeleton Screens 101](https://www.nngroup.com/articles/skeleton-screens/)）。
header と footer だけを描く frame 型の skeleton は勧めていない（同）。
TanStack Router の `pendingMs` の既定 1000 ms は、この 1 秒の線と一致する。

**Skeleton の支援技術向けの扱い**：Adrian Roselli は、`aria-busy="true"` を尊重する screen reader が少ないため、skeleton の装飾要素を `aria-hidden` にし、読み込み中であることを文字で伝える構成を勧める（[More Accessible Skeletons](https://adrianroselli.com/2020/11/more-accessible-skeletons.html)）。
MDN は、live region は内容を変える前に DOM に置く必要があり、初期 markup に含めるのが確実だとする（[ARIA live regions](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Guides/Live_regions)）。
同じ文書は、`role="alert"` は挿入と同時でも読み上げられることが多いと補足している。

**空状態**：NN/g は、空状態で system の状態を伝え、何が表示される場所かを教え、主要な作業への直接の導線を置くことを勧める（[Designing Empty States in Complex Applications](https://www.nngroup.com/articles/empty-state-interface-design/)）。
何も表示しない空白は、読み込み中や error と区別できない（同）。
同じ文書は、まだ何も登録していない場合と、検索で何も見つからない場合の両方を空状態の場面に挙げている。
促す操作は前者が作成、後者が条件の変更であり、文言と導線を分ける必要がある。

**Error Boundary の粒度**：React は、すべての component を包む必要はなく、error message を出すことに意味がある単位で置くよう勧める（Catching rendering errors with an Error Boundary）。

**shadcn の規則**：shadcn の公式 agent skill は、空状態に `Empty`、読み込み中の placeholder に `Skeleton` を使うこと、Dialog、Sheet、Drawer に Title を必ず付けること（見せないなら `sr-only`）、余白を `space-*` ではなく `flex` と `gap-*` で作ることを定める（[skills/shadcn/SKILL.md](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/skills/shadcn/SKILL.md)）。

### アンチパターン

- loader で preload する route で、画面の component が `useQuery` の `isPending` を見て独自の skeleton を出す。
  router の pending 表示と役割が重なり、分岐はほとんど通らない（推測。loader 完了まで suspense に任せる lifecycle から導いた）。
- 背景の再取得の失敗で、表示済みの一覧を error 表示に置き換える（TkDodo）。
- 1 秒未満で終わる load に skeleton を出してちらつかせる（NN/g）。
  `pendingMs` を 0 にすると起きやすい。
- skeleton に `role="status"` だけを付け、装飾の要素を支援技術に晒す（Roselli）。
- 空の一覧で何も表示しない（NN/g）。
- error の表示に `error.message` をそのまま出す。
  fetch の例外は英語の技術的な文言であり、現 `i18n.md` は API 由来の文言を backend が解決した Problem Details に限っている。
  router 同梱の `ErrorComponent` を既定のまま使うのも同じ問題を持つ。
- Dialog に Title を付けない（APG、shadcn skill）。
- 操作の確認を通常の `Dialog` で作る。
  APG は確認の prompt を alert dialog の例に挙げている。

### デファクトスタンダード

**shadcn の Base UI 版の registry**（main、commit `295a1f1`、確認済み）：

- [`ui/skeleton.tsx`](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/apps/v4/registry/bases/base/ui/skeleton.tsx) は `animate-pulse` の `div` だけで、ARIA 属性を持たない。
  支援技術への伝え方は使う側に任されている。
- [`ui/spinner.tsx`](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/apps/v4/registry/bases/base/ui/spinner.tsx) は `role="status"` と英語固定の `aria-label="Loading"` を持つ。
  現リポジトリで使うなら `aria-label` を catalog の文言に変える必要がある。
- [`ui/empty.tsx`](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/apps/v4/registry/bases/base/ui/empty.tsx) は `Empty`、`EmptyHeader`、`EmptyMedia`、`EmptyTitle`、`EmptyDescription`、`EmptyContent` からなる複合 component で、`registry:ui` である（[Empty の文書](https://ui.shadcn.com/docs/components/base/empty)）。
- `alert-dialog`、`dialog`、`sidebar` も `registry:ui` である。
  `sidebar` は `registry:hook` の `use-mobile` に依存する（[ui/_registry.ts](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/apps/v4/registry/bases/base/ui/_registry.ts)）。
- block の `dashboard-01` は `app-sidebar.tsx`、`site-header.tsx`、`nav-main.tsx` などを `registry:component` として持つ（[blocks/_registry.ts](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/apps/v4/registry/bases/base/blocks/_registry.ts)）。
- CLI は `registry:ui` を `aliases.ui` に、それ以外の component を `aliases.components` に、hook を `aliases.hooks` に置く（[components.json の aliases](https://ui.shadcn.com/docs/components-json#aliases)、[registry-item.json](https://ui.shadcn.com/docs/registry/registry-item-json)）。
  現 `frontend/components.json` ではそれぞれ `@/components/ui`、`@/components`、`@/hooks` である。

**bulletproof-react**（commit `9506629`、確認済み）：

- [docs/project-structure.md](https://github.com/alan2207/bulletproof-react/blob/9506629ed003a561c6627735480cce4994244bb4/docs/project-structure.md) は、`components` を app 全体の共有 component、`features` を機能単位とし、feature 間の import を避けて app 層で合成するよう勧める。
  shared から features、features から app への一方向を ESLint の `import/no-restricted-paths` で強制する例も示す。
- 実装は layout を [`apps/react-vite/src/components/layouts/`](https://github.com/alan2207/bulletproof-react/tree/9506629ed003a561c6627735480cce4994244bb4/apps/react-vite/src/components/layouts)、確認 dialog を [`components/ui/dialog/confirmation-dialog/`](https://github.com/alan2207/bulletproof-react/tree/9506629ed003a561c6627735480cce4994244bb4/apps/react-vite/src/components/ui/dialog/confirmation-dialog)、error fallback を [`components/errors/main.tsx`](https://github.com/alan2207/bulletproof-react/blob/9506629ed003a561c6627735480cce4994244bb4/apps/react-vite/src/components/errors/main.tsx) に置いている。

**Feature-Sliced Design**（[Layers](https://feature-sliced.design/docs/reference/layers)、確認済み）：

- `shared/ui` は UI kit であり、business logic を持たなければ、会社のロゴや page layout のような business の見た目を持つ component を置いてよい。
- 現在の文書は Widgets 層を勧めていない。
  画面固有の合成は pages、複数の page で再利用する操作は features、business 文脈の無い UI は shared、app 全体の layout は app に置くとしている。
- `components`、`hooks`、`types` のような segment 名は、中身の種類を表すだけで用途を表さないので勧めていない。

### 現リポジトリへの当てはめ

**既存の docs と ADR との整合**：`routing-and-state.md` は初期描画の server state を loader から preload すると定めており、Loading と Error を route の境界に寄せる案と合う。
`testing.md` は、loading、success、empty、error、再試行のうち画面が提供する状態を検証すると定めている。
`ui-and-style.md` は `components/ui` を feature 非依存の primitive に限り、業務 component を feature に置く。
`architecture.md` は、トップレベル `shared` を作らず、二つ以上の feature で同じ責務を確認してから共有へ移すと定める。
現 `main.tsx` は router の既定の状態表示を設定しておらず、error 時は同梱の英語の `ErrorComponent` が出る（確認済み）。

**旧リポジトリとの比較**：旧は loader を使わず、component が `useQuery` の結果で Loading、Error、Empty、Content の順に early return していた。
現の loader preload の方針では Loading と Error の分岐が route 側へ移るので、旧の順序の規則はそのまま当てはまらない。
旧が自作した `ErrorMessage`、`EmptyState`、`ConfirmDialog` のうち、空状態と確認 dialog は shadcn の `Empty` と `AlertDialog` で代わりが利く。
旧の skeleton は `<output>` を root にしていたが、`output` は HTML 上は計算結果の要素である。
Roselli の構成（装飾を `aria-hidden` にし、文字で読み込み中を伝える）の方が意図に近い。
現 lint も `div role="status"` を `output` に直すよう求めるので（確認済み）、live region が要る箇所でどちらを使うかは決める必要がある。

**推奨案**：

1. 状態表示の分担を `routing-and-state.md` か `ui-and-style.md` に書く。
   - 初期描画の data は loader で `ensureQueryData` し、component は `useSuspenseQuery` で読む。
     Orval は `useSuspenseQuery: true` で suspense 用の Hook を生成し、`get<Operation>QueryOptions` を loader と共有できる（[Orval の output 設定](https://orval.dev/docs/reference/configuration/output)、[React Query guide](https://orval.dev/docs/guides/react-query)。生成設定はグループ A の範囲）。
   - Loading は `defaultPendingComponent`、Error は `defaultErrorComponent`、存在しない resource は loader の `notFound()` と `defaultNotFoundComponent` で受ける（[Not Found Errors](https://tanstack.com/router/latest/docs/framework/react/guide/not-found-errors)）。
     画面ごとに変えたいときだけ route に書く。
   - 画面の component は Empty と Content だけを分ける。
     初期描画に要らない補助の query を `useQuery` で読むときは、data を優先する順序にする。
2. primitive は shadcn の registry から `components/ui` に入れる。
   `Skeleton`、`Empty`、`AlertDialog` は、最初の画面で使う時点で追加する。
   `Spinner` を入れるなら `aria-label` を catalog の文言にする。
3. 確認 dialog は feature の中で `AlertDialog` を組んで作る。
   包む component は、二つ目の feature で同じ形が出てから作る。
4. skeleton は装飾を `aria-hidden` にし、読み込み中の文言を一つだけ支援技術に伝える。
   live region は route の pending 表示の外側（アプリシェル）に常設すると MDN の助言に合う（推測。実装時に screen reader で確かめる）。
5. 置き場所は三つに分けて考える。
   - **primitive**：`components/ui`。
     CLI の既定と ADR-025 のとおりである。
   - **router の既定の状態表示**（pending、error、not found）：router が全 route で使うので、二つの feature での確認を待たずに作る理由がある。
     業務語彙を持たず、feature に依存しない。
   - **アプリシェル**（header、sidebar、layout）：認証の pathless layout か `__root.tsx` から使う。
     sidebar の項目は複数 feature の route を並べるので、ADR-032 の「複数 feature をまたぐ合成は route で行う」に従うと、項目の定義は route 側に置くことになる。

**利用者が決める論点**：

1. router の既定の状態表示とアプリシェルをどこに置くか。
   候補は次の三つである。
   - `components/` の直下。
     shadcn の `registry:component` の既定であり、bulletproof-react の `components/layouts` と `components/errors` に近い。
   - `components/app/` のような下位 directory。
     `components/ui` と並ぶ責務名を一つ増やす。
   - トップレベルの `app/`。
     FSD の app 層に近い。
     `architecture.md` は `app` を禁止せず、今は作らないとしている。

   どれを選んでも、`architecture.md` の依存方向の図と「共有コード」節の更新が要る。
   「トップレベル `shared` を作らない」とは衝突しない（`shared` を作るわけではない）。
   ただし同じ節の「共有物の責務を `components/ui`、`lib`、`api/generated`、`i18n` で直接表せる」という前提は成り立たなくなる。
2. shadcn の `Sidebar` を使うか。
   使うと `use-mobile` が `@/hooks/use-mobile.ts` に入り、ADR-032 の「トップレベル `hooks` ディレクトリは作らない」と衝突する。
   `components.json` の `aliases.hooks` を変えるか、ADR-032 を改める必要がある。
3. live region に `output` を使うか、`div role="status"` を使って `jsx-a11y/prefer-tag-over-role` を外すか。
4. `useSuspenseQuery` を標準にするか。
   採るなら Orval の生成設定（グループ A）と組で決める。

## C13 Link を Button の見た目で描画する方法

### 標準

- `a` 要素の content model は transparent であり、interactive content、`a` 要素、`tabindex` 属性を持つ子孫を含められない（[a 要素](https://html.spec.whatwg.org/multipage/text-level-semantics.html#the-a-element)）。
- `button` 要素の content model は phrasing content であり、interactive content と `tabindex` 属性を持つ子孫を含められない（[button 要素](https://html.spec.whatwg.org/multipage/form-elements.html#the-button-element)）。
- `href` を持つ `a` と `button` はどちらも interactive content である（[Interactive content](https://html.spec.whatwg.org/multipage/dom.html#interactive-content)）。
  したがって `<a><button>` も `<button><a>` も仕様に反する。
- APG の Button Pattern は、button の動作と link の動作は別物であり、見た目と role を機能に合わせるのがよいとする（[Button Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/button/)）。

### ベストプラクティス

- Base UI の `render` prop は部品の描画要素を差し替える。
  描画する独自 component は ref を転送し、受け取った props を DOM 要素に展開する必要がある（[Composition](https://base-ui.com/react/handbook/composition)）。
- Base UI 1.8.0 の Button 文書は、Button は button の semantics（`role="button"`、keyboard 操作、disabled 状態）を強制するので link に使わないこと、link を button の見た目にしたいなら `<a>` を CSS で直接整えることを定める（[Rendering links as buttons](https://base-ui.com/react/components/button#rendering-links-as-buttons)）。
- source でも、`nativeButton` が false のときは `role: 'button'` を付けている（[useButton.ts の 226 行目](https://github.com/mui/base-ui/blob/v1.8.0/packages/react/src/internals/use-button/useButton.ts#L226)、確認済み）。
- shadcn の Base UI 版の Button 文書は、link を button の見た目にするには `buttonVariants` を使い、`<Button render={<a />} nativeButton={false} />` を link に使わないよう明記している（[Button の As Link](https://ui.shadcn.com/docs/components/base/button#as-link)）。
- TanStack Router の `Link` は `className` を受け取る。
  router の型付けを保ったまま独自の link component を作るときは `createLink` を使う（[Custom Link](https://tanstack.com/router/latest/docs/framework/react/guide/custom-link)）。

### アンチパターン

- `<Link><Button /></Link>`。
  HTML の content model に反する。
  現 lint では検出されない（確認済み）。
- `<Button render={<Link />} nativeButton={false}>`。
  `a` の link の role が `button` に上書きされ、支援技術は遷移先を持つ link として扱えなくなる（Base UI と shadcn の文書、source）。
- shadcn の公式 agent skill の [`rules/base-vs-radix.md`](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/skills/shadcn/rules/base-vs-radix.md) は、`<Button render={<a href="/docs" />} nativeButton={false}>` を正しい例として載せている（確認済み）。
  同じ repository の Button 文書と矛盾しており、Base UI の文書と source は Button 文書の側を支持する。
  この skill を読む AI エージェントがこの例に従う可能性がある（推測）。
- 旧リポジトリの `asChild` は Radix 版の API であり、Base UI 版には無い。

### デファクトスタンダード

- shadcn の Base UI 版の公式の手順は、`buttonVariants` を `<a>` に当てる形である（上記の As Link）。
- shadcn の registry の [`ui/button.tsx`](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/apps/v4/registry/bases/base/ui/button.tsx) は `Button` と `buttonVariants` の両方を export する（確認済み）。
- 現リポジトリの [`frontend/src/components/ui/button.tsx`](../../../../frontend/src/components/ui/button.tsx) は `Button` だけを export している。
  一時コピーで `buttonVariants` も export すると `react/only-export-components` が失敗した（確認済み）。
  これが削った理由だと考えられるが、記録は見つけていない（推測。commit `160c677` の追加時からこの形である）。

### 現リポジトリへの当てはめ

**既存の docs と ADR との整合**：`ui-and-style.md` は native semantics を不要な ARIA で置き換えないと定め、`component-design.md` は link の文言が遷移先を表すことを求める。
どちらも Base UI と shadcn の推奨と合う。
ADR-025 は、feature と route が Base UI を直接 import せず `components/ui` を使うと定める。

**旧リポジトリとの比較**：旧は自作の lint rule `no-button-inside-link` で入れ子を禁じ、`asChild` で解決していた。
入れ子が誤りだという判断は現でもそのまま使えるが、解決の書き方は Base UI 版に合わせて変える必要がある。

**推奨案**：

- 遷移は `<Link to=... className={buttonVariants({ variant: "outline" })}>` で書く。
  現 lint（`shadcn/require-static-classes` など）は通る（確認済み）。
- 保存、削除、dialog を開くといった操作は `Button` にする。
  dialog の trigger は `render={<Button />}` で合成する（shadcn skill の base-vs-radix）。
- `ui-and-style.md` に一項目を足す。
  lint rule は違反が出てから考える。

**利用者が決める論点**：`buttonVariants` をどう公開するか。

1. `components/ui/button.tsx` から export し、`src/components/ui/**` の override で `react/only-export-components` に `allowExportNames: ["buttonVariants"]` を与える（[only-export-components](https://oxc.rs/docs/guide/usage/linter/rules/react/only-export-components.html)）。
   shadcn CLI の出力と同じ形を保てる。
   この option は lint に HMR で安全だと伝えるだけで、Fast Refresh の実際の挙動は変えない（推測）。
2. `buttonVariants` を別ファイル（例：`components/ui/button-variants.ts`）に移す。
   lint の例外は要らないが、shadcn CLI で button を更新するたびに分け直す手間が出る。
3. `createLink` で link 用の component を `components/ui` に作り、内部で class を当てる。
   `to` の型付けを保てるが、component が一つ増える。

## C15 enum の表示 mapping と区分値

### 標準

- TypeScript の `satisfies` は、式の型を変えずに型への適合を検査する。
  `satisfies Record<Keys, unknown>` で key の過不足を検出できる（[TypeScript 4.9 の satisfies](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html#the-satisfies-operator)）。
- 型注釈の `Record<Keys, T>` でも key の不足は検出できるが、値の型が `T` に広がる（同文書の palette の例）。
- Orval 8.36.0 は enum を既定で、`as const` の object とそれと同名の型として生成し、`union` 型だけの生成も選べる。
  TypeScript の `enum` 生成は `erasableSyntaxOnly` と両立しない（[enumGenerationType](https://orval.dev/docs/reference/configuration/output#enumgenerationtype)）。
  現 `tsconfig.json` は `erasableSyntaxOnly: true` である。

### ベストプラクティス

- 表示名は i18n の catalog に置き、key を型で検査する。
  現リポジトリの i18next の型設定では、区分値の union から作る template literal の key（``t(`orderStatus.${status}`)``）が、catalog に無い値を型エラーにした（TypeScript 7.0.2、一時コピーで確認済み）。
  i18next の文書は、literal 型が失われる場合には `as const` が要ると書いている（[Type error - template literal](https://www.i18next.com/overview/typescript#type-error-template-literal)）。
- 色は semantic token で表す。
  shadcn の skill は、状態の色を Badge の variant か semantic token で表し、`bg-blue-500` のような生の色を使わないとする（SKILL.md）。
- Base UI 版の Badge の variant は `default`、`secondary`、`destructive`、`outline`、`ghost`、`link` であり、成功や警告の色は無い（[ui/badge.tsx](https://github.com/shadcn-ui/ui/blob/295a1f114a138f23b5dfee0e0c6812394dfeb90c/apps/v4/registry/bases/base/ui/badge.tsx)、確認済み）。
  足すときは `:root` と `.dark` に token を定義し、`@theme inline` で Tailwind に渡す（[Adding New Tokens](https://ui.shadcn.com/docs/theming#adding-new-tokens)）。
- 網羅を `switch` で書くなら、Oxlint の `typescript/switch-exhaustiveness-check`（pedantic、型情報が要る）が union の case の漏れを検出する（[rule 文書](https://oxc.rs/docs/guide/usage/linter/rules/typescript/switch-exhaustiveness-check.html)）。
  現設定は pedantic を error にしている。

### アンチパターン

- 表示名を mapping の値に直書きする（旧の `STATUS_LABELS`）。
  `i18n.md` と `react/jsx-no-literals` の方針に反し、言語を足すたびに mapping を複製することになる。
- 色を Tailwind の生の色で書く（旧の `bg-emerald-100`）。
  現 `shadcn/no-raw-colors` が拒否し、dark mode の token とも切り離される。
- `Record<string, ...>` や `Partial<Record<...>>` で受ける。
  key の不足を検出できない。
- Orval が生成した区分値を、feature 側で手書きの定数として複製する。
  backend の変更は生成物に届いても、手書きの側には届かない。

### デファクトスタンダード

生成された型を key にして `satisfies Record<...>` で表示属性を網羅する形は、TypeScript 公式の例に沿う。
主要 OSS での実例は、この調査では集めていない。

### 現リポジトリへの当てはめ

**既存の docs と ADR との整合**：[`docs/web-api/response-body.md`](../../../../docs/web-api/response-body.md) がすでに判断を持っている。
区分値はコード値だけを返し、区分値の一覧と表示名は frontend が持ち、区分値を取得する API は作らず、表示名は i18n の catalog に置く。
公開 API とモバイルアプリの場合は見直すとしている。
したがって、定数か参照 API かの判断は、区分値については決定済みである。
ただし [`docs/web-api/headers.md`](../../../../docs/web-api/headers.md) は「区分値のように利用者間で共有できる応答」の cache を API ごとに許可するとしており、区分値の API を作らない方針と読み合わせにくい（軽微な食い違い）。

**旧リポジトリとの比較**：旧は「デプロイ無しに値を追加、変更する必要があるか」で定数と参照 API を分けた。
現の用語でいえば、この基準は区分値と、利用者や管理者が実行時に増減するマスタ（カテゴリなど）とを分ける線に当たる。
マスタを区分値ではなく独自の API を持つ resource として扱えば、`response-body.md` と矛盾しない。

**推奨案**：

- `component-design.md` か `i18n.md` に次を書く。
  - 区分値の型は Orval の生成型を使い、手書きで複製しない。
  - 表示名は catalog の key（例：`orderStatus.DRAFT`）に置き、生成型の union から key を作って型で網羅を検査する。
  - 色や icon など表示属性の mapping は `as const satisfies Record<生成型, ...>` で書き、色は Badge の variant か semantic token にする。
  - デプロイ無しに値を変える必要がある値は、区分値ではなくマスタとして API から取得する。
- `headers.md` の例示を「マスタのように」に改めるかを確認する。

**利用者が決める論点**：

1. mapping を置く場所。
   区分値を所有する feature の中に置くのが自然である。
   複数の feature が同じ区分値を表示するときの共有方法は、report.md の Q-e の 3（feature 間で能力を共有する形）と同じ問題になる。
2. 成功や警告の semantic token を足すか。
   足すなら `dark-mode.md` の配色の規則と合わせる。
3. Orval の `enumGenerationType` を既定の `const` のままにするか。
   `const` は同名の値と型を一つのファイルで宣言するが、一時コピーで feature のコードに同じ形を書くと `eslint(no-redeclare)` が失敗した（確認済み）。
   lint の除外は今 `routeTree.gen.ts` だけなので、生成物を lint の対象から外すかどうかをグループ A と合わせて決める必要がある。

## C16 component の分割の目安

### 標準

分割の目安を定める仕様は無い。
React は、component は一つのことだけに関心を持つのが理想であり、大きくなったら小さな子 component に分けると述べる（[Thinking in React](https://react.dev/learn/thinking-in-react)）。
component の定義を別の component の中に入れ子にしないことも求めている（[Nesting and organizing components](https://react.dev/learn/your-first-component#nesting-and-organizing-components)）。

### ベストプラクティス

- Kent C. Dodds は、再利用、性能、state の複雑さといった実際の問題が出てから分ければよく、早すぎる抽象より大きいままの component の方が保守しやすいとする（[When to break up a component into multiple components](https://kentcdodds.com/blog/when-to-break-up-a-component-into-multiple-components)）。
- `import/max-dependencies` の rule 文書は、依存の多い module は責務が多すぎる兆候だとする。
  上限は既定 10 で、`import type` も既定で数え、`ignoreTypeImports` で除外できる（[import/max-dependencies](https://oxc.rs/docs/guide/usage/linter/rules/import/max-dependencies.html)）。
- `eslint/max-lines` の既定は 300 行であり、rule 文書は推奨値が 100 から 500 行の範囲に分かれていると述べる（[eslint/max-lines](https://oxc.rs/docs/guide/usage/linter/rules/eslint/max-lines.html)）。

### アンチパターン

- import の数を減らすためだけに分ける。
  責務の境界とずれた component ができ、props の受け渡しが増える（Kent C. Dodds が挙げる prop drilling の問題からの推測）。
- Loading や Error のためだけの component を画面ごとに作る。
  route の境界で受ければ要らない（C8）。
- lint の上限を disable comment で回避する。
  現設定は `reportUnusedDisableDirectives` を error にしているが、効いている disable comment は通る。

### デファクトスタンダード

- 現 lint の実効値は、`import/max-dependencies` が 10、`eslint/max-lines` が 300 行、`react/no-multi-comp`（restriction）による 1 ファイル 1 component である。
  `max-lines-per-function` は off である（確認済み）。
- `react/no-multi-comp` は既定で stateless な component も数え、`ignoreStateless` で緩められる（[react/no-multi-comp](https://oxc.rs/docs/guide/usage/linter/rules/react/no-multi-comp.html)）。
- Airbnb の React style guide も、1 ファイルに React component を一つだけ置くことを基本の規則にしている（[Basic Rules](https://github.com/airbnb/javascript/tree/master/react#basic-rules)）。

### 現リポジトリへの当てはめ

**既存の docs と ADR との整合**：`component-design.md` の「責務の分け方」は、単一の責務を超える component を分けることと、単純な問題に複雑な構造を作らないことを定めており、React と Kent C. Dodds の推奨と合う。
数値の上限は lint が持っている。

**旧リポジトリとの比較**：旧は `import/max-dependencies` を避けるため、詳細画面を最初から `DetailHeader`、`DetailState`、`DetailCard`、`DetailSkeleton` に分けていた。
これは、画面の component が状態表示、削除の確認 dialog、本体をすべて抱えていたことへの対処である。
現の構成では `DetailState` に当たる分岐が route の境界へ移り、`DetailSkeleton` は route の pending 表示になる。
操作（削除の確認 dialog と mutation）を持つ header と、表示の本体を分けるのは、state を持つ部分と持たない部分の境界なので、責務による分割として残る。

**推奨案**：

- docs に数値は書かず、lint の設定を正本にする。
- `component-design.md` の「責務の分け方」に、lint の上限に当たったら import の数ではなく責務の境界で分けること、状態表示は route の境界に出すこと（C8）を一文ずつ足す。
- `react/no-multi-comp` があるので、route 固有の `pendingComponent` や `errorComponent` は route file とは別のファイルに置く必要がある、と `routing-and-state.md` に書く。

**利用者が決める論点**：

1. `import/max-dependencies` の `ignoreTypeImports` を true にするか。
   型だけの import は実行時の結合を増やさないので、数えない方が rule の趣旨に近いという考え方がある（推測）。
2. `react/no-multi-comp` の `ignoreStateless` を使うか。
   小さな表示だけの子 component を同じファイルに置けるようになる。
   どちらも実際に困ってから変えれば足りる。

## C5 ファイル名の表記

### 標準

ファイル名の表記を定める言語仕様は無く、制約は file system と tool から来る。

- Git の文書は APFS、HFS+、FAT、NTFS を大文字小文字を区別しない file system として挙げ、`git clone` と `git init` が `core.ignoreCase` を自動で設定すると述べる。
  この設定では、大文字小文字だけが違う名前を同じファイルとして扱う（[git-config の core.ignoreCase](https://git-scm.com/docs/git-config#Documentation/git-config.txt-coreignoreCase)）。
- TypeScript の `forceConsistentCasingInFileNames` は、disk 上と異なる大文字小文字で import すると error にする（[tsconfig reference](https://www.typescriptlang.org/tsconfig/#forceConsistentCasingInFileNames)）。
  既定値は true である（TypeScript 5.9.3 の source で確認済み。7.0.2 の既定は確かめていない）。
  現 `tsconfig.json` はこの option を明示していない。

### ベストプラクティス

- React 公式は表記を定めていない。
  学習用の例は `Gallery.js` のように component 名と同じ PascalCase にしている（[Importing and Exporting Components](https://react.dev/learn/importing-and-exporting-components)）。
- TanStack Router の route file は URL の segment と token（`$`、`_`、`.`、`-`、`()`、`index`、`route`）を名前に使うので、PascalCase にする余地は無い（C6 の命名規則）。
  公式 example は route の外に置く component を `-components/Expensive.tsx` のように PascalCase にしている。
- shadcn CLI は kebab-case で生成する（`button.tsx`、`alert-dialog.tsx`、`app-sidebar.tsx`、`use-mobile.ts`）。
- eslint-plugin-unicorn の `filename-case` は既定が kebabCase であり、大文字小文字を区別する file system でも予測しやすく import しやすい path にすることを目的に挙げる。
  `index.*` と `$` で始まる segment は検査の対象外である（[filename-case](https://github.com/sindresorhus/eslint-plugin-unicorn/blob/main/docs/rules/filename-case.md)）。
- Oxlint の `unicorn/filename-case` も既定で kebab-case を強制する（[Oxlint unicorn/filename-case](https://oxc.rs/docs/guide/usage/linter/rules/unicorn/filename-case.html)）。
  route 名の `$`、`_`、`.`、`()` は一時コピーで違反にならなかった（確認済み）。
- bulletproof-react は、すべての `.ts` と `.tsx` を kebab-case にする規則を ESLint で強制する例を示す（[project-standards.md の File naming conventions](https://github.com/alan2207/bulletproof-react/blob/9506629ed003a561c6627735480cce4994244bb4/docs/project-standards.md)）。
- Airbnb の React style guide は、ファイル名を PascalCase（`ReservationCard.jsx`）にするとしている（[Naming](https://github.com/airbnb/javascript/tree/master/react#naming)）。

### アンチパターン

- 大文字小文字だけを変える rename を、大文字小文字を区別しない file system の上で `git mv` を使わずに行う。
  Git は同じファイルとみなすので、rename が記録されないことがある（core.ignoreCase の説明からの推測）。
- import の大文字小文字を disk 上の名前と違えて書く。
  大文字小文字を区別しない file system では動き、Linux の CI で失敗する。
  `forceConsistentCasingInFileNames` がこれを検出する。
- docs と lint で表記を食い違わせる。
  現リポジトリはこの状態にある（下記）。

### デファクトスタンダード

| 系統 | ファイル名 | 実例のパス（確認済み） |
| --- | --- | --- |
| shadcn registry | kebab-case | `apps/v4/registry/bases/base/ui/alert-dialog.tsx` |
| bulletproof-react | kebab-case | `apps/react-vite/src/features/discussions/components/discussions-list.tsx` |
| Base UI | PascalCase | `packages/react/src/button/Button.tsx` |
| Excalidraw | PascalCase | `packages/excalidraw/components/Actions.tsx` |
| Grafana | PascalCase | `public/app/core/components/CardButton.tsx` |
| Supabase Studio | PascalCase の directory | `apps/studio/components/interfaces/APIKeys` |
| Mattermost webapp | snake_case の directory | `webapp/channels/src/components/about_build_modal` |
| TanStack Router の example | route file は token 付きの小文字、route 外は PascalCase | `examples/react/kitchen-sink-file-based/src/routes/expensive/-components/Expensive.tsx` |

React 全体で一つのデファクトは定まっていない。
shadcn と TanStack Router の file-based routing を使う構成に限れば、生成物と route file が小文字になるので、PascalCase を採ると一つの `src/` に二つの表記が混ざる。

### 現リポジトリへの当てはめ

**既存の docs と ADR との整合**：`vite.config.ts` は `style` カテゴリを error にし、`unicorn/filename-case` を個別に設定していないので、既定の kebab-case が効いている。
一時コピーで `OrderListItem.tsx` を置くと失敗した（確認済み）。
一方で `component-design.md` は feature の component のファイル名を PascalCase（`OrderListItem.tsx`）にすると定め、ADR-032 と `architecture.md` は `<Feature>Page.tsx` としている。
このまま最初の feature を作ると、`task fe-check`（`vp check` を実行する）が失敗する見込みが高い。
現にあるファイル（`button.tsx`、`resolve-locale.ts`、`i18next.d.ts`、`-index.test.tsx`）はすべて kebab-case である。

**旧リポジトリとの比較**：旧は ADR-0012 で kebab-case を選び、`unicorn/filename-case` で強制していた。
根拠に挙げた大文字小文字を区別しない file system の問題と、URL とファイル名の表記の一致は、上の Git と unicorn の文書と一致する。
「ファイル名は配置のための名前、識別子は中身の種類を表す」という旧の第三の根拠は、旧の設計上の主張であり、外部の情報源は確認していない。

**推奨案**：kebab-case に統一する。

- component 名は PascalCase のまま、ファイル名は kebab-case にする（`order-list-item.tsx` が `OrderListItem` を export する）。
- `component-design.md` の命名の項、ADR-032 と `architecture.md` の `<Feature>Page.tsx` を、`<feature>-page.tsx` のような kebab-case の表記に改める。
- lint の設定は変えなくてよい。
  kebab-case の強制はすでに効いている。

**利用者が決める論点**：

1. kebab-case と PascalCase のどちらにするか。
   PascalCase を採るなら、`unicorn/filename-case` の `cases` で両方を許すか、`src/features/**` の override で PascalCase を指定する。
   前者は統一の検査を失い、後者は shadcn の `components/ui` と feature で表記が分かれる。
2. ADR-032 を改訂するか、新しい ADR にするか。
   ADR-032 は Proposed なので、`docs/adr/conventions.md` に従って判断する。
3. `forceConsistentCasingInFileNames: true` を `tsconfig.json` に明示するか。
   現 `tsconfig.json` は、TypeScript 6 以降で既定値になった option でも editor の差をなくすために明示する方針を `strict` で取っているので、同じ扱いにする考え方がある。
