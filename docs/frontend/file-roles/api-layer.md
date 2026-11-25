---
type: Convention
title: ファイルの役割：API層
description: frontend/packages/api/src（apiFetch、CSRF の token、Orval の生成物、テストの準備）と frontend/apps/web/src/api（createQueryClient、preloadQuery）に置く API 層の定義、置き場所と命名、必須の記述、生成物の使い方、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。API の呼び出し方、transport、QueryClient の設定を変えるとき、生成した client を画面から使うときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：API層

API 層は、画面とバックエンドの HTTP API のあいだに置くファイルであり、`@frontend/api`（`frontend/packages/api/src`）とアプリの `src/api/` に分かれる。
`@frontend/api` は、Orval の生成物と、生成物が呼ぶ `apiFetch` と、CSRF の token の読み取りを持ち、`fetch` を使えるのはこの package だけである。
アプリの `src/api/` は、`QueryClient` を作る `createQueryClient` と、loader が使う `preloadQuery` を持つ。
生成物は手で書き換えず、生成元を変えて再生成する。

## 定義

画面が API を呼ぶ形を 1 か所で決めないと、feature ごとに Fetch の関数、型、エラーの扱いが分かれる。
**API 層**は、その形を決めるファイルの集まりであり、次のファイルを持つ。

- **`packages/api/src/generated/**`**：`openapi/openapi.yaml` から Orval が生成した endpoint、query options、Hook、型、Zod の schema、MSW の handler（[生成API境界](../api-client-orval.md#生成api境界)）。
- **`packages/api/src/api-fetch.ts`**：生成した関数が呼ぶ custom mutator の `apiFetch`。
- **`packages/api/src/api-error.ts`**：非 2xx を表す `ApiProblemError`、エラーの判定、401 の遷移、再試行の判定。
- **`packages/api/src/csrf.ts`**：CSRF の Cookie の値を読む `csrfToken` と、form に入れる値を作る `maskedCsrfToken`。
- **`packages/api/src/idempotency-key.ts`**：利用者の操作ごとに新しい `Idempotency-Key` のヘッダーを作る `idempotencyKeyHeaders`。
- **`packages/api/src/testing/`**：この package のテストが共有する MSW の server と setup。
- **`apps/web/src/api/query-client.ts`**：本番とテストが同じ設定で使う `QueryClient` を作る `createQueryClient`。
- **`apps/web/src/api/preload-query.ts`**：loader で生成 query options の data を cache に入れる `preloadQuery`。

`createQueryClient` と `preloadQuery` は、共有するかを ADR-092 が決めていないのでアプリに残す（[`@frontend/api`の公開](../api-client-orval.md#frontendapiの公開)）。
Orval と custom mutator を採る理由は [ADR-024](../../adr/ADR-024-adopt-orval-for-frontend-api-client.md) に、生成物をコミットする理由は [ADR-052](../../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md) に示す。

API 層は業務の処理を持たない。
複数の query の合成、業務上の既定値、`select` での変換は、使う側の feature に書く（[ルーティング](../routing-and-state.md#ルーティング)）。

## 置き場所と命名

- 手書きのファイルは、`packages/api/src/` の `generated/` の外か、`apps/web/src/api/` に置き、ファイル名を kebab-case にする。
- `src/generated/` に手書きのコードを置かない。
  Orval の `clean` が生成先を消して作り直すためである。
- アプリが import できる入口は、`packages/api/package.json` の `exports` にあるものだけである（[`@frontend/api`の公開](../api-client-orval.md#frontendapiの公開)の表）。
  `exports` にない subpath は、依存を書いていても解決できない。
- custom mutator を足すのは、Problem Details、CSRF、timeout などの共通の transport の要件を組み込みの Fetch で表せないと確かめた場合に限る（[transportとruntime検証](../api-client-orval.md#transportとruntime検証)）。

## 必須の記述

`@frontend/api` の手書きのコードは次を守る。

- 非 2xx は `ApiProblemError` として投げ、`detail` を `message` に入れない。
- problem type は本文の文字列のまま比べ、比べる値を名前付きの定数にする（`csrfTokenInvalidType`、`tenantNotSelectedType`）。
- CSRF の header、403 の再送、送信先の origin の判定は、[transportとruntime検証](../api-client-orval.md#transportとruntime検証)に従う。
- Problem Details の本文を Zod で検証しない。

アプリの `src/api/` は次を守る。

- `main.tsx` は `createQueryClient()` で `QueryClient` を作り、テストの `createTestQueryClient()`（`src/testing/query-client.ts`）も同じ関数から作る。
- `createQueryClient()` は、`QueryCache` と `MutationCache` の `onError` に `redirectToLoginOnUnauthorized` を、`defaultOptions.queries.retry` に `retryUnlessClientError` を渡し、`MutationCache` の `onSettled` で全 query を無効化する（[mutationの後のcache](../routing-and-state.md#mutationの後のcache)）。
- `preloadQuery` は `queryClient.query()` を呼び、無効化された query だけを取得し直す（[状態表示の分担](../routing-and-state.md#状態表示の分担)）。

## 生成物の扱い

生成物の使い方（loader の `preloadQuery`、mutation の後の cache、key の書き方）は [OrvalとAPI境界](../api-client-orval.md)と[フロントエンドのルーティングと状態管理](../routing-and-state.md)に従う。

生成物を変えるときは、契約の生成元（バックエンドの Controller と DTO）か `orval.config.ts` を変え、`task api-gen` か `task api-client-gen` で再生成する（[生成物のGit管理と再生成](../api-client-orval.md#生成物のgit管理と再生成)）。
生成ファイルを直接編集しない。
エージェントによる書き込みは Kiro のフックの `block-generated-writes` が止め（[Kiroのフック](../../agents/kiro-hooks.md)）、フックを通らない書き換えは `task api-client-check` が差分として検出する。

## 依存してよいもの、してはいけないもの

- **`@frontend/api` が依存してよいもの**：同じ package のファイル、`packages/api/package.json` に書いた依存。
- **`@frontend/api` が依存してはいけないもの**：`@frontend/ui`、アプリのコード、`@base-ui/**`、`@grafana/*`、手書きの production source からのテスト用部品（`msw` を含む）。
- **アプリの `src/api/` が依存してよいもの**：`@frontend/api/api-error`、`@tanstack/react-query`。
- **アプリの `src/api/` が依存してはいけないもの**：`@/features/**`、`@/routes/**`、`@/routeTree.gen`、`@base-ui/**`、テスト用部品、`@grafana/*`、global の `fetch` と `XMLHttpRequest`。

`fetch` と `XMLHttpRequest` は `packages/api/**` の外で使えない（[ファイルの範囲ごとの制限](../lint-config.md#ファイルの範囲ごとの制限)）。
テストファイルと `src/testing/` では、テスト用部品と層の import の禁止が外れる。

## 最小の例と典型的な例

最小の例は、`preloadQuery` を loader から呼ぶ route である（`frontend/apps/web/src/routes/_authenticated/_tenant/orders/new.tsx`）。

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

component は同じ生成 query options を `useSuspenseQuery` で読む（`frontend/apps/web/src/features/tenancy/tenant-members-page.tsx`）。

```tsx
  const { data, error } = useSuspenseQuery(getListTenantMembersSuspenseQueryOptions());
```

典型的な例は `createQueryClient` である（`frontend/apps/web/src/api/query-client.ts`）。

```ts
import { redirectToLoginOnUnauthorized, retryUnlessClientError } from "@frontend/api/api-error";
import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";

/** 本番とテストが同じ設定で使う QueryClient を作る。 */
export function createQueryClient(): QueryClient {
  const queryClient: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: redirectToLoginOnUnauthorized }),
    mutationCache: new MutationCache({
      onError: redirectToLoginOnUnauthorized,
      // ponytail: 成功でも失敗（409 を含む）でも、描画中の関係のない query まで全部取り直す。
      // 再取得が性能の問題になったら、mutationKey か meta で絞るか、Orval の mutationInvalidates へ移る（docs/frontend/routing-and-state.md）。
      // Promise を返すので、mutation は再取得が終わるまで pending のままになり、関係のない query が 5xx を返すと再試行と backoff（最大 3 回）の分だけ遅れる。
      // 遅れが画面で問題になったら、refetchType か mutationKey で無効化の範囲を絞る。
      onSettled: () => queryClient.invalidateQueries(),
    }),
    defaultOptions: { queries: { retry: retryUnlessClientError } },
  });
  return queryClient;
}
```

`apiFetch` の本体は `frontend/packages/api/src/api-fetch.ts` にあり、挙動は[transportとruntime検証](../api-client-orval.md#transportとruntime検証)に書いてある。

## 対応するテスト

- `@frontend/api` のテストは Node で走り、`src/testing/setup.ts` が MSW の server の起動と後片付けを全テストで行う。
  生成した handler がない transport の検査は、テストの中で `server.use()` に手書きの handler を渡す（[配置と実行環境](../testing.md#配置と実行環境)）。
  `document` と `location` は `vi.stubGlobal` で置く。
- `api-error.test.ts` は、比べる problem type がコミット済みの `openapi/openapi.yaml` にあることも確かめる。
- `query-client.test.ts` は、`QueryObserver`、`MutationObserver`、MSW で、mutation の後の再取得が終わるまで pending を保つことを確かめる。
- `preload-query.test.ts` は、`createTestQueryClient()` と `vi.fn` の `queryFn` で、cache の再利用と無効化の後の取り直しを確かめる。
- 生成物そのものはテストせず、coverage からも外す。
  生成物を使う手書きのコードの挙動を確かめる（[coverage](../testing.md#coverage)）。

次は `frontend/apps/web/src/api/preload-query.test.ts` の cache の再利用のテストである。

```ts
  it("取得した data を cache に入れ、cache にあれば取得し直さない", async () => {
    const queryClient = createTestQueryClient();
    const queryFn = vi.fn<() => Promise<{ data: string }>>(() =>
      Promise.resolve({ data: "loaded" }),
    );
    const options = { queryKey: ["/api/items"], queryFn };

    await expect(preloadQuery(queryClient, options)).resolves.toStrictEqual({ data: "loaded" });
    await expect(preloadQuery(queryClient, options)).resolves.toStrictEqual({ data: "loaded" });

    expect(queryFn).toHaveBeenCalledOnce();
    expect(queryClient.getQueryData(["/api/items"])).toStrictEqual({ data: "loaded" });
  });
```

## アンチパターン

- feature の中で `fetch` を呼ぶ、または生成物と同じ Fetch の関数、request と response の型、schema を手書きする。
- 生成ファイルを直接編集する。
  次の再生成で消え、`task api-client-check` が失敗する。
- `src/generated/` に手書きの mutator、MSW の server、fixture を置く。
- query key を配列で手書きする。
  Orval の key は URL の文字列を先頭に持つので、手書きの key は一致しない。
- loader から `ensureQueryData` を呼ぶ。
- 生成した MSW の handler を production source から import する。
- token を保存して Authorization header を付ける。
  同一オリジンの session cookie で認証する。
- Orval の `baseUrl` を設定する。
  別の origin へ token を送らないよう、`baseUrl` は設定しない（[transportとruntime検証](../api-client-orval.md#transportとruntime検証)）。

## 作成時のチェックリスト

- [ ] `fetch` と `XMLHttpRequest` を `packages/api` の外で使わない。［Oxlint で検査：no-restricted-globals、no-restricted-properties］
- [ ] 生成物と同じ Fetch の関数、型、schema を手書きしない。［自分で点検］
- [ ] 生成ファイルを直接編集せず、`src/generated/` に手書きのコードを置かない。［Task で検査：api-client-check（エージェントの書き込みは Kiro のフックの block-generated-writes が止める）］
- [ ] アプリから import する手書きのファイルを `exports` に足す。［自分で点検］
- [ ] custom mutator は、組み込みの Fetch で表せない transport の要件があるときだけ足す。［自分で点検］
- [ ] 非 2xx を `ApiProblemError` で投げ、`detail` を `message` に入れない。［自分で点検］
- [ ] problem type を文字列のまま比べ、比べる値がコミット済みの契約にあることをテストで確かめる。［自分で点検］
- [ ] loader は `preloadQuery` と生成した `get<Operation>SuspenseQueryOptions()` を使い、component は同じ options を `useSuspenseQuery` で読む。［自分で点検］
- [ ] `ensureQueryData` を呼ばない。［Oxlint で検査：typescript/no-deprecated］
- [ ] 個別の無効化は `get<Operation>QueryKey()` を使い、key を手書きしない。［自分で点検］
- [ ] 生成した MSW の handler とテスト用部品を production source から import しない。［Oxlint で検査：no-restricted-imports］
- [ ] アプリの `src/api/` から feature、route、route tree を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] `@frontend/api` の `package.json` に `@frontend/ui` とアプリを書かない。［Vitest で検査：lint/workspace-layout.test.js］
- [ ] 本番の `QueryClient` を `createQueryClient()` で作る。［自分で点検］
- [ ] transport の分岐を、MSW の handler を使うテストで確かめる。［自分で点検］
