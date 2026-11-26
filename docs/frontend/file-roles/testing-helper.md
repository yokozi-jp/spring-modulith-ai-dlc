---
type: Convention
title: ファイルの役割：テストの道具
description: src/testing に置く、MSW の handler と fixture を除くテストの道具（Vitest の setupFiles、route の描画、テスト用の QueryClient、処理を止める gate、CSRF の Cookie の差し替え、注文の詳細の画面の操作、Idempotency-Key の記録、Node のテストの翻訳）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。複数のテストが使う準備のコードを足すとき、setupFiles の後片付けを変えるとき、route を描画するテストを書くときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：テストの道具

テストの道具は、複数のテストが同じ準備を同じ形で使うための関数と Vitest の setupFiles であり、`src/testing/` に置く。
道具は、本番の関数を呼び、テストに要る差分だけを変える。
画面を操作して要素を取る道具は、本番の関数を呼ばずに、描画された画面だけを使う。
テストの書き方の正文は[フロントエンドのテストと検証](../testing.md)にあり、テスト基盤の判断理由は [ADR-027](../../adr/ADR-027-adopt-frontend-testing-stack.md) に示す。

## 定義

テストごとに provider や後片付けを書くと、本番と違う設定で描画したり、前のテストの差し替えが次のテストに残ったりする。
**テストの道具**は、その準備を一か所にまとめ、テストから一つの呼出で使えるようにしたコードである。
道具は本番の設定を作り直さない。
`createTestQueryClient` は `createQueryClient` を、`renderRoute` は `routerDefaults` と `routeTree` を使う。

道具は次の種類に分かれる。
ファイルの全体は `src/testing/` を見る。この文書に全ファイルの一覧を持たないのは、道具を足したときに一覧へ書き漏らすからである。

- **setupFiles**（`setup.ts`）：全テストに要る準備と後片付け。package ごとに一つ置き、`vite.config.ts` の `test.setupFiles` から指す
- **本番の設定でテストの対象を組み立てる道具**（`render-route.tsx`、`query-client.ts`）：本番の関数を呼び、テストに要る差分だけを変える
- **処理の流れを操る道具**（`gate.ts`、`idempotency-keys.ts`、`csrf-cookie.ts`）：promise を止める、要求の header を記録する、`document.cookie` を差し替える
- **画面の操作と要素の取得**（`order-detail-screen.ts`）：描画された画面を操作し、要素を取る
- **Node のテストの翻訳**（`catalog-i18n.ts`）：`ja.json` だけを読んだ instance から `t` を取る

MSW の server（`src/testing/msw.ts`）と、業務機能ごとの fixture と handler（`src/testing/<feature>.ts`）は [MSW の handler と fixture](msw-handler.md) が扱う。
E2E test の fixture（`apps/web/e2e/`）は、この役割に含めない（[E2Eテストの方針と書き方](../../e2e/testing-strategy.md)）。

## 置き場所と命名

- `src/testing/` に置く。
  準備のコードを 2 つ目のテストファイルで使うときに作り、1 つのテストだけが使う準備はそのテストファイルに書く（[テストの置き場所](../architecture.md#テストの置き場所)）。
- ファイル名は、作るものを表す kebab-case の名詞にする（`render-route.tsx`、`query-client.ts`、`gate.ts`）。
- export する関数の名前は動詞で始める（`renderRoute`、`createTestQueryClient`、`createGate`）。
- setupFiles は package ごとに `src/testing/setup.ts` の一つにし、その package の `vite.config.ts` の `test.setupFiles` から指す。

## 必須の記述

setupFiles（`setup.ts`）は、次を守る。

- 全テストに要る準備と後片付けを `setup.ts` にまとめ、テストに `configure` と同じ `afterEach` を書かない（[配置と実行環境](../testing.md#配置と実行環境)）。
- `afterEach` で、`cleanup`、`server.resetHandlers()`、`vi.restoreAllMocks()`、`vi.unstubAllGlobals()`、`vi.useRealTimers()` を呼ぶ。
  `apps/web` は、jsdom のときだけ `@/i18n` を動的に import して `ja` に戻す。
- `server.listen({ onUnhandledRequest: "error" })` で、handler のない要求をエラーにする。
- Testing Library の待つ上限は、`inject("asyncWaitTimeoutMs")` で `apps/web/vite.config.ts` から受け取り、値を `setup.ts` に書かない。
- hook は全テストに効かせるため `describe` の外に置き、`vitest/require-top-level-describe` を理由付きのコメントで外す。

ほかの道具は、次を守る。

- route を描画する道具は、`render` の前に `router.load()` で最初の loader を終え、`userEvent.setup()` を呼ぶ（[書き方](../testing.md#書き方)）。
  描画の後に `load` を呼ぶと、loader が 2 回走る。
- 処理を止める道具は待つ上限を持たず、テストが `release` を呼ぶまで止める。
- `@/i18n` は import の時点で `document` を使う。
  Node のテストでも読み込まれる道具（`setup.ts`、`catalog-i18n.ts`）は、`@/i18n` を静的に import しない。
- Lint の規則を行単位で外すときは、`--` の後に理由を書く（`gate.ts`）。
  不要になった抑制は `reportUnusedDisableDirectives` が報告する。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：組み立てに要る本番のコード（`@/api/query-client`、`@/i18n`、`@/router-defaults`、`@/routeTree.gen`、`@/i18n/locales/ja.json`）、Testing Library、user-event、`vite-plus/test`、同じ `src/testing/` のファイル。
  テストファイルと `src/testing/**` では、層の import の制限が外れる（[ファイルの範囲ごとの制限](../lint-config.md#ファイルの範囲ごとの制限)）。
- **依存してはいけないもの**：Base UI、テレメトリ SDK、`vi.mock` と `vi.doMock` による module の差し替え、`vi.waitFor` と `vi.waitUntil`、global の `fetch`。

production source は `@/testing/**`、`msw`、`@testing-library/**` を import できない。
`packages/api` の `src/testing/` は `package.json` の `exports` に載らないので、アプリから import できない。

## 最小の例と典型的な例

最小の例は、`release` を呼ぶまで解決しない promise を作る `apps/web/src/testing/gate.ts` である。

```ts
/** release を呼ぶまで解決しない promise を作る。待つ上限を持たず、MSW の handler や loader をテストが解放するまで止める。 */
export function createGate() {
  // oxlint-disable-next-line eslint/init-declarations -- Promise の executor が同期に代入する。
  let release!: () => void;
  // oxlint-disable-next-line promise/avoid-new -- Promise.withResolvers は tsconfig の lib（ES2023）にない。
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}
```

テストは、止める側の loader か handler で `promise` を返し、確かめたい状態を見てから `release` を呼ぶ（`router-defaults.test.tsx`）。

```tsx
    const loader = createGate();
    renderRouter(
      treeWithLoader(() => loader.promise),
      "/",
      0,
    );

    await expect(screen.findByRole("status")).resolves.toHaveProperty("textContent", "Loading…");

    loader.release();
    await expect(screen.findByText("loaded")).resolves.toBeTruthy();
```

典型的な例は、本番の関数で組み立てて描画する `apps/web/src/testing/render-route.tsx` である（import を省く）。

```tsx
/** main.tsx と同じ provider と router の既定値で、path の route を loader から描画する。 */
export async function renderRoute(
  path: string,
  options?: { locale?: "ja" | "en" },
): Promise<{ router: AnyRouter; queryClient: QueryClient; user: UserEvent }> {
  await i18n.changeLanguage(options?.locale ?? "ja");
  // jsdom は scrollTo を実装していない（scrollRestoration が呼ぶ）。
  vi.spyOn(globalThis, "scrollTo").mockReturnValue();
  const queryClient = createTestQueryClient();
  const router = createRouter({
    history: createMemoryHistory({ initialEntries: [path] }),
    routeTree,
    context: { queryClient },
    ...routerDefaults,
  });
  // 描画の後に呼ぶと Transitioner の load と重なり loader が 2 回走るため、描画の前に最初の loader を終える。
  await router.load();
  // docs/frontend/testing.md のとおり、render の前に setup する。
  const user = userEvent.setup();
  render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  return { router, queryClient, user };
}
```

`createTestQueryClient` は、本番の設定を受け継いで query の再試行だけを外す（`apps/web/src/testing/query-client.ts`）。

```ts
/** 本番と同じ設定の QueryClient から、query の再試行だけを外す。失敗の表示を待たずに確かめるため。 */
export function createTestQueryClient(): QueryClient {
  const queryClient = createQueryClient();
  const defaults = queryClient.getDefaultOptions();
  queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retry: false } });
  return queryClient;
}
```

## 対応するテスト

道具に専用のテストファイルはない。
道具は、それを使う component test と unit test を通して動く。
`src/testing/**` は、`apps/web/vite.config.ts` と `packages/api/vite.config.ts` の `coverage.exclude` で計測から外している。
使われなくなった道具の export は、Knip が未使用の export として報告する。

## アンチパターン

- 道具の中で `new QueryClient({ ... })` から client を作り直す。
  401 の処理と mutation の後の無効化が、テストから外れる。
- テストファイルに、`setup.ts` と同じ後片付けの `afterEach` を書く。
- 待つ上限や固定時間の `setTimeout` を道具に書く。
- Node のテストで `@/i18n` を import する。
  `document` がないため、import の時点で失敗する。
- 1 つのテストだけが使う準備を `src/testing/` に置く。

## 作成時のチェックリスト

- [ ] 2 つ目のテストファイルが使うときに `src/testing/` に置き、1 つだけが使う準備はそのテストファイルに書く。［自分で点検］
- [ ] ファイル名を kebab-case にする。［Oxlint で検査：unicorn/filename-case］
- [ ] 本番の関数（`createQueryClient`、`routerDefaults`、`routeTree`）を呼び、テストに要る差分だけを変える。［自分で点検］
- [ ] 全テストの準備と後片付けは `setup.ts` に置き、テストに `configure` と同じ `afterEach` を書かない。［自分で点検］
- [ ] 待つ上限を書かず、`apps/web/vite.config.ts` の `asyncWaitTimeoutMs` に任せる。［自分で点検］
- [ ] 処理を止める道具は `createGate` を使い、固定時間で待たない。［自分で点検］
- [ ] route を描画する道具は、`render` の前に `router.load()` と `userEvent.setup()` を呼ぶ。［自分で点検］
- [ ] Node のテストでも読み込まれる道具は、`@/i18n` を静的に import しない。［自分で点検］
- [ ] module を差し替えず、`vi.waitFor` と `vi.waitUntil` で待たない。［Oxlint で検査：vitest/no-restricted-vi-methods］
- [ ] production source から `@/testing/**` を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] 使わなくなった道具の export を残さない。［Knip で検査：task fe-knip（未使用の export）］
