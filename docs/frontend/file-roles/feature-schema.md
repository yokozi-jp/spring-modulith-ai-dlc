---
type: Convention
title: ファイルの役割：feature の入力 schema
description: features/<feature>/schemas.ts に置く、送信時の検証の Zod schema と form の値の型の定義、置き場所と命名、生成 schema への条件の重ね方、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。業務フォームの入力の条件を書くとき、生成 schema で表せない条件を足すときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：feature の入力 schema

`features/<feature>/schemas.ts` は、Orval が生成した request の Zod schema を土台に、生成 schema で表せない入力の条件を重ねた送信時の検証の schema と、form の値の型を持つ。
上限の値は生成の定数から取り、誤りの文言は `t` で catalog から引く。
検証の役割と条件の同期の正文は[フォームの入力検証](../form-validation.md)にある。

## 定義

クライアントとサーバーの検証の条件は、OpenAPI の契約を正本にして揃える。
生成の Zod schema だけで表示している入力の条件を表せれば、その schema を送信時の検証に使い、`schemas.ts` を作らない。
**feature の入力 schema** は、生成 schema で表せない項目単位の条件を、生成 schema に重ねて書いたものである（[検証条件の同期](../form-validation.md#検証条件の同期)）。

今の例は `features/orders/schemas.ts` だけである。
生成 schema は明細の件数を `min(0)`、数量を任意としているため、1 行以上と数量の必須を重ねる。

`schemas.ts` は、form の値の型と、新しい行の初期値も持つ。
input の値は文字列なので、form の値の型は input の値のまま持ち、送信時の検証で request の型に変換する。

## 置き場所と命名

- `frontend/apps/web/src/features/<feature>/schemas.ts` に置く。
  手書きの条件がないときは作らない（[目標のディレクトリ構成](../architecture.md#目標のディレクトリ構成)）。
- schema を返す関数は、送る操作の名前に `FormSchema` を付ける（`draftOrderFormSchema`、`changeOrderLinesFormSchema`）。
- form の値の型は `<対象>Values` にする（`DraftOrderValues`、`OrderLinesValues`）。
- 入力欄の属性に渡す上限の定数は、値の名前に `Min` か `Max` を付ける（`quantityMax`、`customerOrderCodeMax`）。

## 必須の記述

- schema は、`t` を引数で受ける関数で作る。
  誤りの文言は、各条件の `{ error: t("...") }` で catalog から引く。
- 生成の schema（`@frontend/api/zod/<tag>/<tag>.zod`）を、`extend` か `pick` で土台にする。
- 生成の定数がある上限の値は、その定数（`draftOrderBodyCustomerOrderCodeMax`）から取って export する。
  Orval は `min(1)` のような下限の定数を生成しないため、下限は `quantityMin = 1` のように書く。
  入力欄の `maxLength`、`min`、`max` も同じ定数を使う（[feature の component](feature-component.md)）。
- 画面の中だけの値（React の key の `rowKey`）と文字列の数量は、`transform` で request の形にする。
  Hook は `schema.parse(value)` の結果を mutation の本文に渡す。
- 生成 schema に条件を重ねる理由を、コメントに書く。
- 表示している入力の条件と同じ条件だけを検証し、緩くも厳しくもしない。

schema は、[feature の Hook](feature-hook.md) が TanStack Form の `validators: { onSubmit: schema }` に渡す。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@frontend/api/zod/<tag>/<tag>.zod` の生成 schema と定数、`zod`、i18next の `TFunction` の型、同じ feature の module。
- **依存してはいけないもの**：`react` と `react-i18next`、同じ feature の画面、component、Hook、他の feature、`@/routes/**` と `@/routeTree.gen`、テスト用の部品、テレメトリの SDK。

## 最小の例と典型的な例

最小の例は、明細の変更の送信時の検証を作る `changeOrderLinesFormSchema` である（`features/orders/schemas.ts` の抜粋）。
生成の `ChangeOrderLinesBody` から明細だけを取り、明細の条件を重ねる。

```ts
/** 明細の変更の送信時の検証。lockNo は form の値に持たず、送るときに足す。 */
export function changeOrderLinesFormSchema(t: TFunction) {
  return ChangeOrderLinesBody.pick({ lines: true }).extend({
    lines: orderLinesSchema(t, changeOrderLinesBodyLinesMax),
  });
}
```

`lockNo` を form の値に持たないのは、編集を始めた版を Hook が持ち続けるためである（[入力と版](../update-conflicts.md#入力と版)）。

典型的な例は、form の値の型、上限の定数、作成の送信時の検証を持つ `features/orders/schemas.ts` である（抜粋）。

```ts
import {
  ChangeOrderLinesBody,
  changeOrderLinesBodyLinesMax,
  DraftOrderBody,
  draftOrderBodyCustomerOrderCodeMax,
  draftOrderBodyLinesItemQuantityMax,
  draftOrderBodyLinesMax,
} from "@frontend/api/zod/ordering/ordering.zod";
import type { TFunction } from "i18next";
import { z as zod } from "zod";

/** 入力欄の値。数量は input の値のまま文字列で持ち、送信時の検証で数値にする。 */
export interface OrderLineValues {
  /** React の key に使う、画面の中だけの識別子。送信しない。 */
  rowKey: string;
  productId: string;
  quantity: string;
}

export interface OrderLinesValues {
  lines: OrderLineValues[];
}

export interface DraftOrderValues extends OrderLinesValues {
  customerOrderCode: string;
}

export function newLine(): OrderLineValues {
  return { rowKey: crypto.randomUUID(), productId: "", quantity: "1" };
}
export const quantityMin = 1;
export const quantityMax = draftOrderBodyLinesItemQuantityMax;
export const customerOrderCodeMax = draftOrderBodyCustomerOrderCodeMax;

// 生成 Zod は明細の件数を min(0)、数量を任意としているため、画面の入力条件（1 行以上、数量は必須）を重ねる。
function orderLinesSchema(t: TFunction, max: number) {
  const quantityRange = t("orders.form.errors.quantityRange", {
    min: quantityMin,
    max: quantityMax,
  });
  const line = zod
    .object({
      rowKey: zod.string(),
      productId: zod.uuid({ error: t("orders.form.errors.productRequired") }),
      quantity: zod
        .string()
        .trim()
        .min(1, { error: t("orders.form.errors.quantityRequired") })
        .transform(Number)
        .pipe(
          zod
            .number({ error: quantityRange })
            .int({ error: quantityRange })
            .min(quantityMin, { error: quantityRange })
            .max(quantityMax, { error: quantityRange }),
        ),
    })
    // rowKey は画面の中だけの値なので、送る本文から外す。
    .transform(({ productId, quantity }) => ({ productId, quantity }));
  return zod
    .array(line)
    .min(1, { error: t("orders.form.errors.linesRequired") })
    .max(max, { error: t("orders.form.errors.linesTooMany", { max }) });
}

/** 作成の送信時の検証。客先注文番号は前後の空白を除いて 1 文字以上 30 文字以下にする。 */
export function draftOrderFormSchema(t: TFunction) {
  return DraftOrderBody.extend({
    customerOrderCode: zod
      .string()
      .trim()
      .min(1, { error: t("orders.form.errors.customerOrderCodeRequired") })
      .max(customerOrderCodeMax, {
        error: t("orders.form.errors.customerOrderCodeTooLong", { max: customerOrderCodeMax }),
      }),
    lines: orderLinesSchema(t, draftOrderBodyLinesMax),
  });
}
```

## 対応するテスト

`schemas.ts` の条件は、form を持つ画面の component test で、送信時の誤りの文言と、要求を送らないことを確かめる。
今は `features/orders/order-new-page.test.tsx` の「送信時の検証の誤りを入力欄の直下に出し、送信しない」が、必須の条件を確かめている（[feature の component](feature-component.md#対応するテスト)に引用がある）。
手書きした条件が OpenAPI の制約と一致することは、レビューとこのテストで確かめる（[検証条件の同期](../form-validation.md#検証条件の同期)）。

## アンチパターン

- 生成 schema と同じ条件を、`schemas.ts` に手書きする。
- 生成の定数がある上限を、数値の literal で書く。
  契約の上限が変わっても、画面の検証と入力欄の属性が追従しない。
- Zod の既定の誤りの文言を、そのまま画面に出す。
- 表示している入力の条件より緩い条件や厳しい条件を検証する。
- Problem Details や API の応答の検証の schema を手書きする。
- `schemas.ts` の関数の中で `useTranslation` を呼ぶ。

## 作成時のチェックリスト

- [ ] 生成 schema で表せない条件があるときだけ、`schemas.ts` を作る。［自分で点検］
- [ ] 生成 schema を `extend` か `pick` で土台にし、同じ条件を手書きしない。［自分で点検］
- [ ] 生成の定数がある上限はその定数から取り、入力欄の属性にも同じ定数を渡す。［自分で点検］
- [ ] 誤りの文言を、`t` を引数で受けて catalog から引く。［Oxlint で検査：react/rules-of-hooks（Hook を呼ばないことだけ。文言の引き方は自分で点検）］
- [ ] 表示している入力の条件と同じ条件だけを検証する。［自分で点検］
- [ ] form の値の型を input の値のまま持ち、送らない値と型の変換を `transform` で書く。［自分で点検］
- [ ] 生成 schema に条件を重ねる理由をコメントに書く。［自分で点検］
- [ ] 関数を関数宣言で書く。［Oxlint で検査：func-style］
- [ ] 他の feature を import しない。［Oxlint で検査：feature-boundaries/no-cross-feature-import］
- [ ] form を持つ画面のテストで、手書きの条件の誤りと、要求を送らないことを確かめる。［自分で点検］
