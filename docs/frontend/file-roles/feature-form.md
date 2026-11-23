---
type: Convention
title: ファイルの役割：feature の form の組み立て
description: features/<feature>/form.ts に置く、TanStack Form の useAppForm と withFieldGroup の組み立てと、入力欄の誤りと選択肢の補助関数の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。複数の form や入力欄が form の型を共有するとき、form.ts を作るか判断するときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：feature の form の組み立て

`features/<feature>/form.ts` は、その feature の form が共有する `useAppForm` と `withFieldGroup` を作り、入力欄の誤りと選択肢の補助関数を持つ。
入力欄を共有する form が二つ以上あるときだけ作る。
補助関数は React を使わず、Node 環境の unit test で確かめる。

## 定義

作成の form と変更の form が同じ入力欄を描画するとき、入力欄の component が form の型を知らなければ、form ごとに入力欄を複製することになる。
**feature の form の組み立て**は、TanStack Form の `createFormHook` で feature の form の Hook と field group の作り方を一つにまとめるファイルである（[React Hooksと状態](../routing-and-state.md#react-hooksと状態)）。

今の例は `features/orders/form.ts` だけである。
作成の form（`useDraftOrderForm`）と明細の変更の form（`useOrderLinesEditor`）が `useAppForm` を呼び、明細の入力欄（`OrderLinesFields`）と客先注文番号の入力欄（`CustomerOrderCodeField`）が `withFieldGroup` で作られる。

入力の条件を書く `schemas.ts` は [feature の入力 schema](feature-schema.md)、`form.ts` 以外の補助は [feature の module](feature-module.md) の役割である。

## 置き場所と命名

- `frontend/apps/web/src/features/<feature>/form.ts` に置く。
  入力欄を共有する form が一つしかないときは作らず、その Hook の中で `useForm` を呼ぶ。
- export する名前は、`createFormHook` が返す `useAppForm` と `withFieldGroup` をそのまま使う。
- 補助関数は、扱う値を表す動詞か名詞にする（`firstErrorMessage`、`productOptions`）。
- テストはファイルの隣に `form.test.ts` で置く。

## 必須の記述

- `createFormHookContexts` と `createFormHook` は、このファイルで一度だけ呼ぶ。
  `fieldComponents` と `formComponents` は、使う component が生じるまで空にする。
- `useAppForm` は [feature の Hook](feature-hook.md) が呼び、`withFieldGroup` は [feature の component](feature-component.md) が呼ぶ。
  `form.ts` は component も Hook も import しない。
- TanStack Form の `errors` は Standard Schema の issue の配列なので、入力欄の誤りは `firstErrorMessage` で最初の `message` を取る。
  `message` がなければ `undefined` を返す。
- 文言が要る補助関数は、`t` を最初の引数で受け、Hook を呼ばない（`productOptions(t, products, selectedId)`）。
- 選択肢を作る関数は、`t` と元の data を受け、選択肢の配列を返す。
  何を選択肢に含めるかは、業務の規則に従って関数の中で決める。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@tanstack/react-form`、`@frontend/api/models` の型、i18next の `TFunction` の型、同じ feature の module（`order-status.ts`）。
- **依存してはいけないもの**：`react`、`react-i18next`、同じ feature の画面、component、Hook、`schemas.ts` の schema、`@/routes/**` と `@/routeTree.gen`、他の feature、global の `fetch` と `XMLHttpRequest`。

## 最小の例と典型的な例

最小の例は、`features/orders/form.ts` の form の組み立てである。

```ts
const { fieldContext, formContext } = createFormHookContexts();

export const { useAppForm, withFieldGroup } = createFormHook({
  fieldContext,
  formContext,
  fieldComponents: {},
  formComponents: {},
});
```

典型的な例は、同じファイルの `productOptions` である。
販売中の商品と、選択中の販売終了の商品だけを選択肢にする。

```ts
export function productOptions(
  t: TFunction,
  products: readonly ProductSummaryResponse[],
  selectedId: string,
): ProductOption[] {
  const options: ProductOption[] = [];
  for (const { productId, productName: name, salesStatus } of products) {
    if (salesStatus === "ON_SALE") {
      options.push({ productId, label: name });
    } else if (productId === selectedId) {
      options.push({
        productId,
        label: t("orders.form.discontinuedProduct", {
          name,
          status: salesStatusLabel(t, salesStatus),
        }),
      });
    }
  }
  return options;
}
```

## 対応するテスト

補助関数は、Node 環境の unit test で、入力と戻り値の対応を確かめる。
`features/orders/form.test.ts` が、`firstErrorMessage` の最初の issue と空の配列、`productOptions` の選択肢の絞り込みを確かめている。
`t` は `@/testing/catalog-i18n` のものを渡し、catalog の文言を確かめる。

`useAppForm` と `withFieldGroup` は、これらを使う画面の component test が、入力と送信を利用者の操作で確かめる（[feature の画面](feature-page.md#対応するテスト)）。
form の組み立てだけを確かめるテストは書かない。

## アンチパターン

- 入力欄を共有する form が一つしかないのに `form.ts` を作る。
- `form.ts` に schema や入力の条件を書く。
  条件は `schemas.ts` に集める。
- `form.ts` で `useTranslation` を呼び、補助関数を Hook にする。
- `fieldComponents` に、まだ使う component のない部品を先に登録する。
- 入力欄の誤りを `errors[0].message` で直接読み、`errors` の形を component ごとに仮定する。
- 選択肢を component の中で絞り込み、描画のたびに同じ判定を複製する。

## 作成時のチェックリスト

- [ ] 入力欄を共有する form が二つ以上あるときだけ、`form.ts` を作る。［自分で点検］
- [ ] `createFormHookContexts` と `createFormHook` をこのファイルで一度だけ呼ぶ。［自分で点検］
- [ ] `fieldComponents` と `formComponents` に、使う component だけを登録する。［自分で点検］
- [ ] 入力欄の誤りを `firstErrorMessage` で取る。［自分で点検］
- [ ] 文言が要る補助関数は `t` を引数で受け、Hook を呼ばない。［Oxlint で検査：react/rules-of-hooks］
- [ ] 関数を関数宣言で書く。［Oxlint で検査：func-style］
- [ ] 画面、component、Hook、`schemas.ts` を import しない。［自分で点検］
- [ ] 他の feature を import しない。［Oxlint で検査：feature-boundaries/no-cross-feature-import］
- [ ] 補助関数の unit test を隣に置く。［自分で点検］
