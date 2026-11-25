---
type: Convention
title: ファイルの役割：feature の Hook
description: features/<feature>/hooks/ に置く custom Hook（use-*.ts）の定義、置き場所と命名、mutation と通知と form の状態の持ち方、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。画面や component から状態と操作を Hook に分けるとき、mutation の callback、409 の扱い、lockNo の送り方を書くときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：feature の Hook

feature の Hook は、feature の画面か component が使う状態と操作を用途の名前でまとめた custom Hook であり、`features/<feature>/hooks/use-<用途>.ts` に置く。
mutation は生成 Hook で送り、画面遷移と通知は `mutate` の callback に置き、Effect を使わない。
戻り値は、状態と、event handler に渡す `handle<操作>` の関数を持つ object にする。

## 定義

画面の component に mutation、form、通知の状態をすべて書くと、一つの component が多くを知り、多くを処理する。
**feature の Hook** は、それらの状態と、状態を変える操作を、[feature の画面](feature-page.md)や [feature の component](feature-component.md) から分けた custom Hook である。
Hook を作る条件、名前、置き場所の正文は [React Hooksと状態](../routing-and-state.md#react-hooksと状態)と [Hookの名前とファイル](../routing-and-state.md#hookの名前とファイル)にある。

今の feature の Hook は、次のどれかを持つ。

- **操作**：生成の mutation Hook で確定、取消、役割の変更を送り、結果を通知にする（`use-order-status-actions.ts`、`use-member-actions.ts`）。
- **form**：TanStack Form の form と、サーバーの 400 の誤りと、409 の後の選択肢を持つ（`use-draft-order-form.ts`、`use-order-lines-editor.ts`）。
- **画面の組み立て**：画面の data を読み、上の Hook が共有する一つの通知を持つ（`use-order-detail.ts`）。

React の state を使わない処理は Hook にせず、[feature の module](feature-module.md) の関数にする。

## 置き場所と命名

- `frontend/apps/web/src/features/<feature>/hooks/` に置く。
  `hooks/` は、feature に custom Hook ができた時点で作る。
- 名前は `use` に用途を付ける（`useDraftOrderForm`、`useOrderStatusActions`、`useMemberActions`）。
  `use` で始まる名前は、Hook を呼ぶ関数だけに付ける。
- ファイル名は Hook の名前の kebab-case にする（`use-draft-order-form.ts`）。

## 必須の記述

- `export function use<用途>(...)` の関数宣言にし、ファイルに一つだけ export する。
- mutation は `@frontend/api/endpoints/<tag>/<tag>` の生成の `use<Operation>()` で送る。
- `Idempotency-Key` を求める mutation は、利用者が操作するたびに `idempotencyKeyHeaders()` を呼び、`mutate` の変数の `headers` に入れる（[ADR-126](../../adr/ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md)）。
  同じ値を送るのは、同じ変数で呼ばれる自動の再試行だけにする。
  自動の再試行は `createQueryClient()` の mutation の既定が行い、Hook は再試行を書かない（[状態表示の分担](../routing-and-state.md#状態表示の分担)）。
- 画面遷移と通知は、`mutate` に渡す callback（`onSuccess`、`onError`）に置き、`useMutation` の options に置かない（[mutationの後のcache](../routing-and-state.md#mutationの後のcache)）。
- 失敗の通知は、`@/lib/notice` の `failureNotice` か、feature の module の関数（`formFailure`）で作る。
  見出しは backend の `title` を使い、説明は操作ごとの catalog の文言にする（[更新の競合（409）の画面の扱い](../update-conflicts.md)）。
- 既存の版を変える操作の `lockNo` と 409 の扱いは、[更新の競合（409）の画面の扱い](../update-conflicts.md)に従う。
- 送信中の 2 回目は送らない。
- 複数の Hook が一つの通知を共有するときは、通知の state を呼び出し側に置き、`setNotice` を引数で受ける（`use-order-status-actions.ts`、`use-member-actions.ts`）。
- cache を読むときは、生成の `get<Operation>QueryKey()` を使い、key を手書きしない。
- 文言は `useTranslation` の `t` で catalog から引く。
- 戻り値は object にし、event handler に渡す関数を `handle<操作>` と名付ける（`handleConfirm`、`handleDiscard`）。
- Hook の中だけで使う純粋な関数は、export せずに同じファイルに置く（`createdOrderId`、`linesValues`）。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@frontend/api/endpoints/<tag>/<tag>` の生成 Hook、query options、query key、`@frontend/api/models` の型、`@frontend/api/idempotency-key` の `idempotencyKeyHeaders`、`@tanstack/react-query`（`useSuspenseQuery`、`useQueryClient`）、`@tanstack/react-router` の `useNavigate`、`react` の `useState`、`react-i18next`、`@/lib/notice`、同じ feature の module と Hook。
- **依存してはいけないもの**：`@/routes/**` と `@/routeTree.gen`、他の feature、Base UI（`@base-ui/**`）、テスト用の部品、テレメトリの SDK、global の `fetch` と `XMLHttpRequest`、Effect（`useEffect`、`useLayoutEffect`、`useInsertionEffect`、`useEffectEvent`）。

## 最小の例と典型的な例

最小の例は、所属の管理の画面の操作を持つ `features/tenancy/hooks/use-member-actions.ts` である。
表示中の `lockNo` を送り、結果を呼び出し側の通知に入れる。

```ts
import {
  useChangeTenantMemberRole,
  useRemoveTenantMember,
} from "@frontend/api/endpoints/tenancy/tenancy";
import { idempotencyKeyHeaders } from "@frontend/api/idempotency-key";
import { useTranslation } from "react-i18next";

import { failureNotice } from "@/lib/notice";
import type { Notice } from "@/lib/notice";

/**
 * 役割の変更と所属の取り外し。押した時点の表示中の lockNo を送る（表示している版と送る版が同じなので、黙って差し込まない）。
 * 409 は通知だけにし、最新の一覧は全 query の無効化に任せる。自動で再送しない。
 */
export function useMemberActions(lockNo: number, setNotice: (notice: Notice | undefined) => void) {
  const { t } = useTranslation();
  // 毎回作り直される mutation の結果ではなく、安定した mutate を取り出して React Compiler の memo を効かせる。
  const { mutate: changeRole, isPending: isChanging } = useChangeTenantMemberRole();
  const { mutate: removeMember, isPending: isRemoving } = useRemoveTenantMember();

  function callbacks(success: string) {
    return {
      onSuccess: () => {
        setNotice({ heading: success });
      },
      onError: (error: unknown) => {
        setNotice(
          failureNotice(t, error, {
            notFound: t("tenancy.members.notice.notFound"),
            conflict: t("tenancy.members.notice.conflict"),
            unprocessable: t("tenancy.members.notice.lastAdmin"),
          }),
        );
      },
    };
  }

  return {
    isPending: isChanging || isRemoving,
    handleChangeRole: (userId: string, role: string) => {
      setNotice(undefined);
      changeRole(
        { userId, data: { role, lockNo }, headers: idempotencyKeyHeaders() },
        callbacks(t("tenancy.members.notice.roleChanged")),
      );
    },
    handleRemove: (userId: string) => {
      setNotice(undefined);
      removeMember(
        { userId, params: { lockNo }, headers: idempotencyKeyHeaders() },
        callbacks(t("tenancy.members.notice.removed")),
      );
    },
  };
}
```

典型的な例は、注文の詳細の画面の `features/orders/hooks/use-order-detail.ts` である。
画面の data を読み、明細の変更と、確定と取消の Hook に一つの通知を共有させ、送信中は送信中の通知を返す。

```ts
import { getFindOrderByIdSuspenseQueryOptions } from "@frontend/api/endpoints/ordering/ordering";
import { getListProductsSuspenseQueryOptions } from "@frontend/api/endpoints/product/product";
import { useSuspenseQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useTranslation } from "react-i18next";

import type { Notice } from "@/lib/notice";

import { useOrderLinesEditor } from "./use-order-lines-editor";
import { useOrderStatusActions } from "./use-order-status-actions";

/** 詳細の画面の data と、明細の変更、確定、取消が共有する 1 つの通知。 */
export function useOrderDetail(orderId: string) {
  const { t } = useTranslation();
  const { data: order } = useSuspenseQuery(getFindOrderByIdSuspenseQueryOptions(orderId));
  const { data: products } = useSuspenseQuery(getListProductsSuspenseQueryOptions());
  const [notice, setNotice] = useState<Notice>();
  const editor = useOrderLinesEditor(orderId, order.data, setNotice);
  const actions = useOrderStatusActions(orderId, order.data.lockNo, setNotice);
  const pending = editor.isPending || actions.isPending;

  return {
    order: order.data,
    products: products.data.items,
    editor,
    actions,
    notice: pending ? { heading: t("orders.form.submitting") } : notice,
    // lockNo と orderId は契約で必須なので、下書きなら更新の操作を出す。
    editable: order.data.status === "DRAFT",
  };
}
```

409 の後に「変更を捨てる」と「適用し直す」を選ばせる form は、`features/orders/hooks/use-order-lines-editor.ts` が持つ。

## 対応するテスト

Hook は、Hook を使う画面の component test で確かめ、Hook ごとのテストファイルを必須にしない（[観点ごとのテスト](../test-strategy.md#観点ごとのテスト)）。
送った要求の本文と回数、通知の文言、選択肢の有無を、画面から見える形で確かめ、Hook を mock しない。

次は、`useMemberActions` が 409 を自動で再送しないことを確かめる `features/tenancy/tenant-members-page.test.tsx` のテストである（抜粋）。

```tsx
  it("409 は backend の title と競合の説明を出し、自動で再送しない", async () => {
    let requests = 0;
    server.use(
      getListTenantMembersMockHandler(members),
      http.put(memberPath, () => {
        requests += 1;
        return problemResponse(409, "ほかの操作が先に更新しました", {
          type: "/problems/version-conflict",
        });
      }),
    );
    const { user } = await renderRoute("/members");

    await user.selectOptions(
      await screen.findByRole("combobox", { name: `利用者 ${memberId} の役割` }),
      "ADMIN",
    );

    await expect(screen.findByText("ほかの操作が先に更新しました")).resolves.toBeTruthy();
    expect(screen.getByText(/ほかの操作で所属が更新されました/u)).toBeTruthy();
    expect(requests).toBe(1);
  });
```

## アンチパターン

- 一回の `useState` を隠すだけの Hook、生成 Hook をそのまま返すだけの Hook、`useMount` のように React の lifecycle を言い換える Hook を作る。
- Hook の作り方、Effect、`useMemo` と `useCallback`、409 の扱い、query key の書き方に反する書き方は、[React Hooksと状態](../routing-and-state.md#react-hooksと状態)と[更新の競合（409）の画面の扱い](../update-conflicts.md)のアンチパターンに従う。

## 作成時のチェックリスト

- [ ] `features/<feature>/hooks/` に置き、Hook を作る条件を [React Hooksと状態](../routing-and-state.md#react-hooksと状態)で確かめる。［自分で点検］
- [ ] ファイル名を Hook の名前の kebab-case にする。［Oxlint で検査：unicorn/filename-case（名前との一致は自分で点検）］
- [ ] Hook を呼ぶ関数の名前を `use` で始める。［Oxlint で検査：react/rules-of-hooks］
- [ ] Hook を呼ばない関数に `use` を付けない。［自分で点検］
- [ ] 関数宣言で書く。［Oxlint で検査：func-style］
- [ ] `useState` の戻り値を値と setter の組に分割代入する。［Oxlint で検査：react/hook-use-state］
- [ ] Effect を使わない。［Oxlint で検査：react-rules/no-effect］
- [ ] mutation を生成 Hook で送り、画面遷移と通知を `mutate` の callback に置く。［自分で点検］
- [ ] 操作のたびに `idempotencyKeyHeaders()` で新しい `Idempotency-Key` を採る。［型検査で検査：生成した変数の型の必須の `headers`（値の採り方は自分で点検）］
- [ ] 失敗の通知は `failureNotice` か feature の module の関数で作り、見出しを backend の `title` にする。［自分で点検］
- [ ] 表示中の `lockNo` を送り、409 を自動で再送しない。［自分で点検］
- [ ] 送信中の 2 回目を送らない。［自分で点検］
- [ ] cache の key に生成の `get<Operation>QueryKey()` を使う。［自分で点検］
- [ ] 戻り値を object にし、event handler に渡す関数を `handle<操作>` と名付ける。［自分で点検］
- [ ] `useMemo` と `useCallback` を先回りして足さない。［自分で点検］
- [ ] route、Base UI、テスト用の部品、テレメトリの SDK を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] 他の feature を import しない。［Oxlint で検査：feature-boundaries/no-cross-feature-import］
- [ ] Hook を使う画面のテストで、送った要求と通知を確かめる。［自分で点検］
