---
type: Convention
title: ファイルの役割：feature の component
description: features/<feature>/components/ に置く、一つの feature の画面の一部を描画する業務 component の定義、置き場所と命名、props と event、form の field group、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。画面の一部を component に分けるとき、表、入力欄、操作の button の component を足すときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：feature の component

feature の component は、一つの feature の画面の一部を描画する業務 component であり、`features/<feature>/components/<名前>.tsx` に一つずつ置いて export する。
data と操作は props で受け、event を受ける props は `on` で始める。
UI primitive は `@frontend/ui` から使い、文言は catalog から引き、HTML の native semantics を保つ。

## 定義

画面の component がすべてを描画すると、一つの component が多くを知り、多くを処理する。
**feature の component** は、[feature の画面](feature-page.md)から責務ごとに分けた、特定の業務機能の data とロジックを持つ component である（[共通componentと業務component](../component-design.md#共通componentと業務component)）。
業務に依存しない UI primitive は、`packages/ui`（`@frontend/ui`）に置き、feature の component にしない。

今の feature の component は、次の三つの形のどれかである。

- **props で受ける component**：data と callback を props で受けて描画する（`orders-table.tsx`、`order-summary.tsx`、`conflict-choices.tsx`、`members-table.tsx`）。
- **form の field group**：TanStack Form の `withFieldGroup` で作り、複数の form が共有する入力欄を描画する（`customer-order-code-field.tsx`、`order-lines-fields.tsx`）。
  `withFieldGroup` は [feature の form の組み立て](feature-form.md)が作る。
- **補足の欄**：画面の主な内容とは別の情報を、suspense なしの生成 Hook で自分で読み、Loading と Error を欄の中で出す（`payment-status.tsx`）。
  この形を使える条件は[状態表示の分担](../routing-and-state.md#状態表示の分担)の例外に限る。

## 置き場所と命名

- `frontend/apps/web/src/features/<feature>/components/` に置く。
  `components/` は、feature に component ができた時点で作る。
- component の名前は PascalCase で業務か役割を表し、ファイル名はその kebab-case にする（`OrdersTable` は `orders-table.tsx`）。
- props は camelCase にし、event を受ける props は `on` で始める（`onDiscard`、`onConfirm`、`onChangeRole`）。
- 命名の正文は [componentの命名と設計](../component-design.md#命名)にある。

## 必須の記述

- `export function <名前>({ ... }: { ... })` の関数宣言で書く。
  field group だけは `export const <名前> = withFieldGroup({ ... })` にし、`render` に `<名前>Render` の名前付きの関数式を渡す。
- 一つのファイルに component を一つだけ置き、component 以外を export しない。
- 文言は `useTranslation` の `t` で catalog から引く。
- 区分値の表示名は、[feature の module](feature-module.md) の関数か、catalog の key の対応で引く（[区分値の表示](../component-design.md#区分値の表示)）。
- button の見た目の link は `<Link className={buttonVariants(...)}>` で書く（[UIの書き方](../ui-and-style.md#uiの書き方)）。
- Tailwind の class は完全な文字列で書く。
- 表は `<th scope="col">`、絞り込みの link は `<nav aria-label>` と `aria-current` のように、native の要素と属性で意味を表す。
- 入力欄は `<label htmlFor>` で名前を付け、`required`、`maxLength`、`min`、`max` を入力の条件に合わせる。
  誤りがある欄には `aria-invalid` を付け、`aria-describedby` で文言を指し、文言を欄の直下に出す（[フォームの入力検証](../form-validation.md)）。
- 入力欄の id は、画面か form の `useId()` を `idPrefix` として受けて組む。
- 状態の変化を読み上げる領域には `<output>` を使い、最初の描画から置く。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@frontend/ui/<部品>`、`@frontend/api/models` の型、`@tanstack/react-router` の `Link`、`react`（`useId`、`useState`）、`react-i18next`、同じ feature の module と component、同じ feature の Hook の型（`ReturnType<typeof useOrderLinesEditor>`）。
  補足の欄だけは、`@frontend/api/endpoints/<tag>/<tag>` の suspense なしの生成 Hook を使う。
- **依存してはいけないもの**：`@/routes/**` と `@/routeTree.gen`、他の feature、Base UI（`@base-ui/**`）、テスト用の部品、テレメトリの SDK、global の `fetch` と `XMLHttpRequest`。

## 最小の例と典型的な例

最小の例は、409 の後の二つの選択肢を出す `features/orders/components/conflict-choices.tsx` である。
操作は二つの callback の props で受け、選んだ後の処理は Hook が持つ。

```tsx
import { Button } from "@frontend/ui/button";
import { useTranslation } from "react-i18next";

/** 409 の後の 2 つの選択肢。live region の外（直後）に置き、読み上げに button の名前を混ぜない。 */
export function ConflictChoices({
  onDiscard,
  onReapply,
}: {
  onDiscard: () => void;
  onReapply: () => void;
}) {
  const { t } = useTranslation();

  return (
    <div className="flex flex-wrap gap-2">
      <Button type="button" variant="outline" onClick={onDiscard}>
        {t("orders.detail.discardChanges")}
      </Button>
      <Button type="button" onClick={onReapply}>
        {t("orders.detail.reapplyChanges")}
      </Button>
    </div>
  );
}
```

典型的な例は、作成の form の客先注文番号の入力欄を描画する field group の `features/orders/components/customer-order-code-field.tsx` である（抜粋）。
送信時の検証の誤りとサーバーの 400 の誤りを欄の直下に出し、上限の文字数は入力 schema と同じ定数を `maxLength` に渡す。

```tsx
/** 作成の客先注文番号の入力欄。409 では重複と断定できないため aria-invalid を付けない。 */
export const CustomerOrderCodeField = withFieldGroup({
  defaultValues: { customerOrderCode: "" },
  props: defaultProps,
  // oxlint-disable-next-line eslint/func-name-matching -- React の component として hook を呼ぶため、名前を大文字で始める。
  render: function CustomerOrderCodeFieldRender({ group, idPrefix, serverError, onFieldChange }) {
    const { t } = useTranslation();
    const id = `${idPrefix}-customer-order-code`;
    const errorId = `${id}-error`;

    return (
      <group.Field name="customerOrderCode">
        {(field) => {
          const error = firstErrorMessage(field.state.meta.errors) ?? serverError;
          return (
            <div className="flex flex-col gap-1">
              <label htmlFor={id}>{t("orders.fields.customerOrderCode")}</label>
              <input
                id={id}
                className="h-8 max-w-sm rounded-md border bg-background px-2 aria-invalid:border-destructive"
                required
                maxLength={customerOrderCodeMax}
                autoComplete="off"
                value={field.state.value}
                onBlur={field.handleBlur}
                onChange={(event) => {
                  field.handleChange(event.target.value);
                  onFieldChange("customerOrderCode");
                }}
                {...(error === undefined
                  ? {}
                  : { "aria-invalid": true, "aria-describedby": errorId })}
              />
              {error === undefined ? undefined : (
                <p id={errorId} className="text-sm text-destructive">
                  {error}
                </p>
              )}
            </div>
          );
        }}
      </group.Field>
    );
  },
});
```

`withFieldGroup` の `props` に渡す `defaultProps` は型を与えるだけで、ファイルの先頭に export せずに置く。

## 対応するテスト

今の feature の component は、それぞれのテストファイルを持たず、使う画面のテストで確かめる（[feature の画面](feature-page.md#対応するテスト)）。
role と label で操作し、表示の変化、送った要求、読み上げの領域の文言を確かめる（[観点ごとのテスト](../test-strategy.md#観点ごとのテスト)）。
coverage のファイルごとの下限は component にもかかる（[coverage](../testing.md#coverage)）。

次は、field group の誤りの表示を確かめる `features/orders/pages/order-new-page.test.tsx` のテストである（抜粋）。

```tsx
  it("送信時の検証の誤りを入力欄の直下に出し、送信しない", async () => {
    const sent = serveDraftOrder(() => new HttpResponse(undefined, { status: 201 }));
    const { user } = await renderRoute("/orders/new");
    await user.clear(await screen.findByLabelText("数量"));

    await user.click(screen.getByRole("button", { name: "作成する" }));

    await waitFor(() => {
      expect(screen.getByLabelText("客先注文番号").getAttribute("aria-invalid")).toBe("true");
    });
    expect(
      ["客先注文番号", "商品", "数量"].map((label) => errorMessageOf(screen.getByLabelText(label))),
    ).toStrictEqual([
      "客先注文番号を入力してください。",
      "商品を選んでください。",
      "数量を入力してください。",
    ]);
    expect(sent).toHaveLength(0);
  });
```

## アンチパターン

- 業務の判定を component に埋め込む。
  React の state を使わない判定は、[feature の module](feature-module.md) の関数にする。
- 補足の欄でない component が、suspense なしの生成 Hook で画面の主な内容を読み、Loading と Error を自分で出す。
- 区分値の表示名を component ごとに分岐で書く。
- `<Link><Button /></Link>` のように link と button を入れ子にする。
- `` `pl-${depth}` `` のように値から class 名を組み立てる。
- button、label、table の代わりに `div` と ARIA を使う。
- 他の component の内部の構造に依存する。
- 入力の誤りを理由に、送信の button を `disabled` にする。

## 作成時のチェックリスト

- [ ] `features/<feature>/components/` に置き、名前を業務か役割を表す PascalCase にする。［自分で点検］
- [ ] ファイル名を component の名前の kebab-case にする。［Oxlint で検査：unicorn/filename-case（名前との一致は自分で点検）］
- [ ] component を関数宣言で書き、一つのファイルに一つだけ書く。［Oxlint で検査：func-style、react/function-component-definition、react/no-multi-comp］
- [ ] component 以外を export しない。［Oxlint で検査：react/only-export-components］
- [ ] data と操作を props で受け、event を受ける props を `on` で始める。［自分で点検］
- [ ] 補足の欄の例外を除き、取得を画面に任せる。［自分で点検］
- [ ] 文言を JSX に直接書かず、catalog の key を `t` で引く。［JSX の子は Oxlint で検査：react/jsx-no-literals。属性の文言は自分で点検］
- [ ] 区分値の表示名を feature の module か catalog の key の対応で引く。［自分で点検］
- [ ] link と button を入れ子にしない。［Oxlint で検査：ui-composition/no-button-in-link］
- [ ] class 名を値から組み立てない。［Oxlint で検査：tailwind-classes/no-dynamic-class-name］
- [ ] `@frontend/ui` の部品に、部品が持つ見た目の class を渡さない。［Oxlint で検査：shadcn/no-restyle］
- [ ] native の要素と属性で意味を表し、入力欄に `label`、入力の条件の属性、`aria-invalid` と `aria-describedby` を付ける。［自分で点検］
- [ ] Effect を使わない。［Oxlint で検査：react-rules/no-effect］
- [ ] route、Base UI、テスト用の部品、テレメトリの SDK を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] 他の feature を import しない。［Oxlint で検査：feature-boundaries/no-cross-feature-import］
- [ ] 使う画面のテストで、role と label から操作して確かめる。［自分で点検］
