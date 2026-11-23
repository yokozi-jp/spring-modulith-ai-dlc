---
type: Convention
title: ファイルの役割：route
description: frontend/apps/web/src/routes に置く TanStack Router の route file と pathless layout の定義、置き場所と命名、loader と beforeLoad の必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。画面の URL を足すとき、loader、beforeLoad、validateSearch、layout を書くとき、route の近くにテストを置くときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：route

route file は、URL と画面を接続する TanStack Router の file-based route であり、`src/routes/` に置いて `Route` を一つ export する。
業務機能の画面は `features` から import して `component` に渡し、初期描画の data は loader から `preloadQuery` で cache に入れる。
ログインとテナントの確認は、pathless layout の `beforeLoad` に置く。
route の責務と形の正文は[フロントエンドのルーティングと状態管理](../routing-and-state.md)にある。

## 定義

URL ごとに、path、path parameter、search parameter、loader、error の境界、画面の component を結び付ける場所が要る。
**route file** は、`createFileRoute`（ルートだけは `createRootRouteWithContext`）で一つの URL か一つの layout を定義するファイルである。
TanStack Router plugin が、route file から `src/routeTree.gen.ts` を生成する。

route file は、業務機能の画面を実装する場所ではない。
業務機能の画面の表示と利用者の操作は [feature の画面](feature-page.md)に置き、route file はその component を `component` に渡す。
複数の feature を一つの画面で使うときは、route で合成する（[業務機能の境界](../architecture.md#業務機能の境界)）。

**pathless layout** は、`_` で始まる directory と、その `route.tsx` である。
URL の path を増やさずに、配下の route に `beforeLoad` と layout の component を共有させる。
今は、ログインとアプリシェルの `_authenticated` と、選んだテナントを確かめる `_authenticated/_tenant` がある（[認証が要る画面とアプリシェル](../routing-and-state.md#認証が要る画面とアプリシェル)）。

## 置き場所と命名

- `frontend/apps/web/src/routes/` に directory 形式で置き、形は[routeの形](../routing-and-state.md#routeの形)に従う。
- 一覧は `index.tsx`、新規は `new.tsx`、詳細は `$<parameter>/index.tsx` にする（`orders/index.tsx`、`orders/new.tsx`、`orders/$orderId/index.tsx`）。
- `route.tsx` は、子が共有する `beforeLoad`、`loader`、`validateSearch`、layout があるときだけ作る。
- ログインが要る画面は `_authenticated/` の下に置く。
  テナントを選んだ後だけ出す業務の画面は、さらに `_tenant/` の下に置く。
- path と parameter の名前は[フロントエンドのURL設計](../url-design.md)に従う。
- route の近くに置くテストは、route の候補から外すため、ファイル名を `-` で始める（`routes/-logged-out.test.tsx`、`routes/_authenticated/_tenant/-index.test.tsx`）。

## 必須の記述

- `export const Route = createFileRoute("<route id>")({ ... })` を一つだけ書く。
- 初期描画の data は、loader で生成の `get<Operation>SuspenseQueryOptions()` を `preloadQuery`（`src/api/preload-query.ts`）に渡して cache に入れる。
  画面は同じ options を `useSuspenseQuery` で読む（[状態表示の分担](../routing-and-state.md#状態表示の分担)）。
- 一つの画面が複数の query を使うときは、loader で `Promise.all` に並べて同時に取得する。
- loader と `beforeLoad` は、`__root.tsx` の `createRootRouteWithContext<{ queryClient: QueryClient }>()` が渡す `context.queryClient` を使う。
- search parameter は `validateSearch` で検証し、loader が使う値だけを `loaderDeps` で取り出す（`orders/index.tsx`）。
- 存在しない resource は、loader で `ApiProblemError` の 404 を捕まえて `notFound()` を投げる。
  表示は router の `defaultNotFoundComponent` が受け持つ。
- route に入る前の判定（テナントを選んでいないときの移動）は、`beforeLoad` で `redirect()` を投げる。
- `redirect()` と `notFound()` は TanStack Router が throw で受け取るため、`typescript/only-throw-error` を行単位で抑制し、理由を書く。
- layout の component は `<Outlet />` を描画する。
- Loading と Error の表示は route file に書かず、`src/router-defaults.ts` の既定の component に任せる。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@tanstack/react-router`、`@frontend/api/endpoints/<tag>/<tag>` の生成 query options、`@frontend/api/zod/<tag>/<tag>.zod` の生成 schema、`@frontend/api/api-fetch` の `ApiProblemError` と `isTenantNotSelected`、`@/api/preload-query`、`@/components/` の共有の component、`@/features/<feature>/` の画面と [feature の module](feature-module.md)（`isOrderStatus`、`listOrdersParams`）。
- **依存してはいけないもの**：Base UI（`@base-ui/**`）、テスト用の部品（`msw`、`@/testing/**`、`@testing-library/**`）、テレメトリの SDK（`@grafana/*`）、global の `fetch` と `XMLHttpRequest`。

## 最小の例と典型的な例

最小の例は、注文の作成の画面を接続する `routes/_authenticated/_tenant/orders/new.tsx` である。
loader が商品の一覧を cache に入れ、`OrderNewPage` が同じ options で読む。

```tsx
import { getListProductsSuspenseQueryOptions } from "@frontend/api/endpoints/product/product";
import { createFileRoute } from "@tanstack/react-router";

import { preloadQuery } from "@/api/preload-query";
import { OrderNewPage } from "@/features/orders/order-new-page";

export const Route = createFileRoute("/_authenticated/_tenant/orders/new")({
  loader: ({ context }) => preloadQuery(context.queryClient, getListProductsSuspenseQueryOptions()),
  component: OrderNewPage,
});
```

典型的な例は、注文の詳細を接続する `routes/_authenticated/_tenant/orders/$orderId/index.tsx` である。
注文と商品を並べて取得し、404 と 400 を not found にする。

```tsx
import { ApiProblemError } from "@frontend/api/api-fetch";
import { getFindOrderByIdSuspenseQueryOptions } from "@frontend/api/endpoints/ordering/ordering";
import { getListProductsSuspenseQueryOptions } from "@frontend/api/endpoints/product/product";
import { createFileRoute, notFound } from "@tanstack/react-router";

import { preloadQuery } from "@/api/preload-query";
import { OrderDetailPage } from "@/features/orders/order-detail-page";

export const Route = createFileRoute("/_authenticated/_tenant/orders/$orderId/")({
  loader: async ({ context, params }) => {
    try {
      await Promise.all([
        preloadQuery(context.queryClient, getFindOrderByIdSuspenseQueryOptions(params.orderId)),
        preloadQuery(context.queryClient, getListProductsSuspenseQueryOptions()),
      ]);
    } catch (error) {
      // 400 は path の UUID の形の誤りで、利用者には存在しない注文と同じに見せる。
      if (error instanceof ApiProblemError && (error.status === 404 || error.status === 400)) {
        // TanStack Router は notFound() の戻り値を投げる形で not found を扱う。
        // oxlint-disable-next-line typescript/only-throw-error
        throw notFound();
      }
      throw error;
    }
  },
  component: OrderDetailPage,
});
```

pathless layout の例は、選んだテナントを確かめる `routes/_authenticated/_tenant/route.tsx` の `Route` である（抜粋）。
同じファイルの `TenantLayout` が、選んだテナントの名前と `<Outlet />` を描画する。

```tsx
export const Route = createFileRoute("/_authenticated/_tenant")({
  beforeLoad: async ({ context }) => {
    try {
      await preloadQuery(context.queryClient, getFindCurrentTenantSuspenseQueryOptions());
    } catch (error) {
      if (isTenantNotSelected(error)) {
        // oxlint-disable-next-line typescript/only-throw-error -- TanStack Router は redirect を throw で受け取る。
        throw redirect({ to: "/tenants" });
      }
      throw error;
    }
  },
  component: TenantLayout,
});
```

## 対応するテスト

業務機能の route は、[feature の画面](feature-page.md#対応するテスト)のテストが `renderRoute`（`src/testing/render-route.tsx`）で route ごと描画して確かめる。
`renderRoute` は、`main.tsx` と同じ provider と `routerDefaults` で router を作り、最初の loader を終えてから描画する。
loader の not found は `features/orders/order-detail-page.test.tsx` が、`beforeLoad` の移動は `features/tenancy/tenants-page.test.tsx` が確かめている。

業務機能に属さない route は、route の近くに `-` で始まるテストを置く（`routes/-logged-out.test.tsx`）。

```tsx
/* @vitest-environment jsdom */

import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vite-plus/test";

import { renderRoute } from "@/testing/render-route";

describe("ログアウト後の画面", () => {
  it("アプリシェルの外に、ログアウトしたことと、ページ全体の遷移でログインし直すリンクを出す", async () => {
    await renderRoute("/logged-out", { locale: "en" });

    await expect(
      screen.findByRole("heading", { level: 1, name: "You have been logged out" }),
    ).resolves.toBeTruthy();
    expect(screen.getByRole("link", { name: "Log in again" }).getAttribute("href")).toBe(
      "/oauth2/authorization/web",
    );
    expect(screen.queryByRole("banner")).toBeNull();
    await waitFor(() => {
      expect(document.title).toBe("Demo application");
    });
  });
});
```

## アンチパターン

- 業務機能の画面の表示と操作を route file に書く。
- component の mount の後に取得を始める。
  loader の取得と直列になり、waterfall ができる。
- loader と画面で異なる query options を使う。
  query key が一致せず、同じ data を二度取得する。
- loader から `ensureQueryData` を直接呼ぶ。
- 存在しない resource の 404 を捕まえず、再試行できるエラーとして表示する。
- 一つの route file に component を二つ以上書く。
- `routeTree.gen.ts` を手で編集する。
  再生成と検査は[生成物](../testing.md#生成物)に従う。
- route の近くのテストを、`-` で始めない名前で置く。
  TanStack Router plugin が、そのファイルを route の候補として扱う。

## 作成時のチェックリスト

- [ ] `src/routes/` に directory 形式で置き、ファイル名を kebab-case にする。［Oxlint で検査：unicorn/filename-case］
- [ ] 一覧、新規、詳細を `index.tsx`、`new.tsx`、`$<parameter>/index.tsx` にし、`route.tsx` は子が共有する `beforeLoad`、`loader`、`validateSearch`、layout があるときだけ作る。［自分で点検］
- [ ] ログインが要る画面は `_authenticated/` の下に、テナントを選んだ後の業務の画面は `_tenant/` の下に置く。［自分で点検］
- [ ] 業務機能の画面は feature の画面を `component` に渡し、route file に表示と操作を書かない。［自分で点検］
- [ ] component を一つのファイルに一つだけ書く。［Oxlint で検査：react/no-multi-comp］
- [ ] 初期描画の data は、loader で生成の `get<Operation>SuspenseQueryOptions()` を `preloadQuery` に渡し、画面と同じ options にする。［自分で点検］
- [ ] loader から `ensureQueryData` を呼ばない。［Oxlint で検査：typescript/no-deprecated］
- [ ] 複数の query は `Promise.all` で並べて取得する。［自分で点検］
- [ ] 存在しない resource は、loader で `notFound()` を投げる。［自分で点検］
- [ ] `redirect()` と `notFound()` を投げる行の抑制に、TanStack Router が throw で受け取る理由を書く。［自分で点検］
- [ ] layout の component は `<Outlet />` を描画する。［自分で点検］
- [ ] 文言を JSX に直接書かず、catalog の key を `t` で引く。［Oxlint で検査：react/jsx-no-literals］
- [ ] Base UI、テスト用の部品、テレメトリの SDK を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] `fetch` と `XMLHttpRequest` を直接使わない。［Oxlint で検査：no-restricted-globals、no-restricted-properties］
- [ ] 業務機能の route は、画面のテストが `renderRoute` で確かめる。業務機能に属さない route は、`-` で始まるテストを route の近くに置く。［自分で点検］
