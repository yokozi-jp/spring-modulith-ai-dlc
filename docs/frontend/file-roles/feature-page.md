---
type: Convention
title: ファイルの役割：feature の画面
description: features/<feature>/ 直下に置く、一つの URL の画面の component（*-page.tsx）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。業務機能の画面を足すとき、画面の取得、見出し、Empty と Content の分け方、form の送信を書くときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：feature の画面

feature の画面は、一つの URL の画面の表示と利用者の操作を持つ React component であり、`features/<feature>/<画面>-page.tsx` に置いて `<画面>Page` を export する。
初期描画の data は、route の loader と同じ生成 query options を `useSuspenseQuery` で読み、Empty と Content だけを分ける。
route の情報は `getRouteApi` で取り、route を import しない。

## 定義

[route file](route.md) は URL と画面の接続だけを持つため、画面の中身を置く場所が別に要る。
**feature の画面**は、route の `component` に渡す、一つの URL の画面の最上位の component である。
見出し、`<title>`、Empty と Content の分岐を持ち、[feature の component](feature-component.md) と [feature の Hook](feature-hook.md) を組み合わせる。

Loading、Error、存在しない resource の表示は、画面ではなく route の境界が受け持つ（[状態表示の分担](../routing-and-state.md#状態表示の分担)）。
画面は、取得を終えた data で Empty と Content を分ける。

## 置き場所と命名

- `frontend/apps/web/src/features/<feature>/` の直下に置く。
- ファイル名は、画面を表す名詞に `-page` を付けた kebab-case にする（`orders-page.tsx`、`order-new-page.tsx`、`order-detail-page.tsx`、`tenants-page.tsx`、`tenant-members-page.tsx`）。
- component の名前は、ファイル名を PascalCase にした名前にする（`OrdersPage`、`OrderNewPage`）。
- テストは画面の隣に `<画面>-page.test.tsx` で置き、観点が多ければ観点の名前で分ける（`order-detail-failures.test.tsx`、`order-detail-payment.test.tsx`）。

## 必須の記述

- `export function <画面>Page()` の関数宣言にし、props を受けない。
- route の情報は、module の先頭で `const routeApi = getRouteApi("<route id>")` を作り、`routeApi.useParams()` か `routeApi.useLoaderDeps()` で読む。
- 初期描画の data は、loader と同じ引数の `get<Operation>SuspenseQueryOptions(...)` を `useSuspenseQuery` で読む。
  loader と画面が同じ引数を組むときは、引数を組む関数を [feature の module](feature-module.md) に置く（`listOrdersParams`）。
- 画面の中に `<title>` を描画する（[Effectの禁止](../routing-and-state.md#effectの禁止)）。
- `<h1>` に id を付け、画面を包む `<section>` の `aria-labelledby` で指す。
- 文言は `useTranslation` の `t` で catalog から引く（[フロントエンドの国際化](../i18n.md)）。
- 操作の結果の通知は `@/components/problem-notice` の `ProblemNotice` に渡す。
- form は `noValidate` を付け、`onSubmit` で `event.preventDefault()` を呼んでから `void form.handleSubmit()` を呼ぶ（[フォームの入力検証](../form-validation.md#送信buttonとhtmlの制約属性)、[Lintを通る書き方](../lint-config.md#lintを通る書き方)）。
- mutation の後の画面遷移と通知は、`mutate` に渡す callback に置く（[mutationの後のcache](../routing-and-state.md#mutationの後のcache)）。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@frontend/api/endpoints/<tag>/<tag>` の生成 query options と生成 Hook（他の tag も使ってよい）、`@frontend/api/models` の型、`@frontend/api/api-fetch` の `ApiProblemError`、`@frontend/ui/<部品>`、`@tanstack/react-query`、`@tanstack/react-router` の `getRouteApi` と `Link`、`react`、`react-i18next`、`@/components/` の共有の component、`@/lib/`、同じ feature の component、Hook、module。
- **依存してはいけないもの**：`@/routes/**` と `@/routeTree.gen`、他の feature、Base UI（`@base-ui/**`）、テスト用の部品、テレメトリの SDK、global の `fetch` と `XMLHttpRequest`。

## 最小の例と典型的な例

最小の例は、注文の一覧の `features/orders/orders-page.tsx` である。
loader と同じ引数で一覧を読み、件数が 0 なら Empty の文言を、それ以外は一覧の表を出す。

```tsx
import { getListOrdersSuspenseQueryOptions } from "@frontend/api/endpoints/ordering/ordering";
import { buttonVariants } from "@frontend/ui/button";
import { useSuspenseQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { OrderStatusFilter } from "./components/order-status-filter";
import { OrdersTable } from "./components/orders-table";
import { listOrdersParams } from "./order-status";

const routeApi = getRouteApi("/_authenticated/_tenant/orders/");

export function OrdersPage() {
  const { t } = useTranslation();
  const { status } = routeApi.useLoaderDeps();
  const { data } = useSuspenseQuery(getListOrdersSuspenseQueryOptions(listOrdersParams(status)));
  const { items } = data.data;

  return (
    <section className="flex flex-col gap-6 px-6 py-10 sm:px-10" aria-labelledby="orders-heading">
      <title>{t("orders.list.title")}</title>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 id="orders-heading" className="text-2xl font-semibold">
          {t("orders.list.title")}
        </h1>
        <Link to="/orders/new" className={buttonVariants()}>
          {t("orders.list.create")}
        </Link>
      </div>
      <OrderStatusFilter status={status} />
      {items.length === 0 ? (
        <p className="text-muted-foreground">{t("orders.list.empty")}</p>
      ) : (
        <OrdersTable items={items} />
      )}
    </section>
  );
}
```

典型的な例は、注文の作成の `features/orders/order-new-page.tsx` の component である（抜粋）。
form の状態と送信は `useDraftOrderForm` に、入力欄は field group の component に分けている。

```tsx
export function OrderNewPage() {
  const { t } = useTranslation();
  const idPrefix = useId();
  const { data: products } = useSuspenseQuery(getListProductsSuspenseQueryOptions());
  const { form, notice, serverErrors, handleFieldChange } = useDraftOrderForm();

  return (
    <section
      className="flex flex-col gap-6 px-6 py-10 sm:px-10"
      aria-labelledby="order-new-heading"
    >
      <title>{t("orders.new.title")}</title>
      <h1 id="order-new-heading" className="text-2xl font-semibold">
        {t("orders.new.title")}
      </h1>
      <ProblemNotice notice={notice} />
      <form
        noValidate
        className="flex max-w-3xl flex-col gap-6"
        onSubmit={(event) => {
          event.preventDefault();
          void form.handleSubmit();
        }}
      >
        <CustomerOrderCodeField
          form={form}
          fields={{ customerOrderCode: "customerOrderCode" }}
          idPrefix={idPrefix}
          serverError={serverErrors.fields.customerOrderCode}
          onFieldChange={handleFieldChange}
        />
        <OrderLinesFields
          form={form}
          fields={{ lines: "lines" }}
          idPrefix={idPrefix}
          products={products.data.items}
          serverErrors={serverErrors}
          onFieldChange={handleFieldChange}
        />
        <Button type="submit" className="self-start">
          {t("orders.new.submit")}
        </Button>
      </form>
    </section>
  );
}
```

## 対応するテスト

画面のテストは jsdom の component test にし、`renderRoute`（`src/testing/render-route.tsx`）で route の loader と `routerDefaults` ごと描画する。
HTTP は Orval が生成した MSW handler を `server.use()` に渡して置き換え、fixture は `src/testing/<feature>.ts` から使う。
role と label で要素を見つけ、`renderRoute` が返す `user` で操作する（[フロントエンドのテストと検証](../testing.md#書き方)）。
画面が出す状態（成功、Empty、失敗、再試行）を確かめる（[状態遷移](../testing.md#状態遷移)）。

次は、`features/tenancy/tenants-page.test.tsx` の Empty の状態のテストである（抜粋）。

```tsx
  it("所属がないときは、招待を待っていることを伝え、テナントのボタンを出さない", async () => {
    server.use(getListMembershipsMockHandler({ items: [] }));
    await renderRoute("/tenants");

    await expect(
      screen.findByRole("heading", { level: 1, name: "招待を待っています" }),
    ).resolves.toBeTruthy();
    expect(screen.getByText(/テナントの管理者に招待されると/u)).toBeTruthy();
    expect(screen.queryByRole("list")).toBeNull();
    expect(screen.getByRole("button", { name: "ログアウト" })).toBeTruthy();
  });
```

## アンチパターン

- loader と異なる引数の query options で読む。
  query key が一致せず、loader が入れた cache を使わずに取得し直す。
- 画面の中に、読み込み中、取得の失敗、存在しない resource の表示を書く。
- `error.message` を画面に出す。
- route を import して、route の情報を読む。
- 他の feature の画面や component を import する。
  複数の feature を使う画面は、route で合成する。
- 入力の誤りを理由に、送信の button を `disabled` にする。
- import の上限に当たったときに、責務の境界ではなく import の数で分ける（[責務の分け方](../component-design.md#責務の分け方)）。

## 作成時のチェックリスト

- [ ] `features/<feature>/` の直下に、画面を表す名詞に `-page` を付けた名前で置く。［自分で点検］
- [ ] ファイル名を kebab-case にする。［Oxlint で検査：unicorn/filename-case］
- [ ] component を関数宣言で書き、一つのファイルに一つだけ書く。［Oxlint で検査：func-style、react/function-component-definition、react/no-multi-comp］
- [ ] 画面のファイルから component 以外を export しない。［Oxlint で検査：react/only-export-components］
- [ ] route を import せず、route の情報は `getRouteApi` で取る。Base UI、テスト用の部品、テレメトリの SDK も import しない。［Oxlint で検査：no-restricted-imports］
- [ ] 他の feature を import しない。［Oxlint で検査：feature-boundaries/no-cross-feature-import］
- [ ] 初期描画の data は、loader と同じ引数の生成 suspense query options を `useSuspenseQuery` で読む。［自分で点検］
- [ ] Loading、Error、Not Found を画面に書かず、Empty と Content だけを分ける。［自分で点検］
- [ ] `<title>` を描画し、`<h1>` の id を `<section>` の `aria-labelledby` で指す。［自分で点検］
- [ ] 文言を JSX に直接書かず、catalog の key を `t` で引く。［Oxlint で検査：react/jsx-no-literals］
- [ ] Effect を使わない。［Oxlint で検査：react-rules/no-effect］
- [ ] mutation の後の画面遷移と通知を、`mutate` に渡す callback に置く。［自分で点検］
- [ ] form に `noValidate` を付け、送信の button を入力の誤りで `disabled` にしない。［自分で点検］
- [ ] async 関数を event handler に直接渡さない。［Oxlint で検査：typescript/no-misused-promises］
- [ ] import の上限に当たったら、責務の境界で component か Hook に分ける。［Oxlint で検査：import/max-dependencies（分け方は自分で点検）］
- [ ] `fetch` と `XMLHttpRequest` を直接使わない。［Oxlint で検査：no-restricted-globals、no-restricted-properties］
- [ ] 画面のテストを画面の隣に置き、`renderRoute` で描画して、画面が出す状態を確かめる。［自分で点検］
- [ ] テスト名を、利用者から見える振る舞いの日本語で書く。［Oxlint で検査：vitest/valid-title（日本語を含むことだけ。振る舞いかは自分で点検）］
