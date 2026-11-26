---
type: Convention
title: ファイルの役割：アプリの共有component
description: frontend/apps/web/src/components に置くアプリの共有component（アプリシェル、router の既定の状態表示、操作の結果の通知）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。アプリシェルや router の既定の表示を変えるとき、複数の feature が使う component を components に置くか決めるときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：アプリの共有component

アプリの共有componentは、特定の feature の業務語彙に属さない画面の部品であり、`frontend/apps/web/src/components/` の直下に 1 ファイル 1 component で置く。
今あるのは、アプリシェル、router の既定の pending、error、not found の表示、操作の結果の通知、アプリシェルの外の状態の画面である。
文言は catalog から出し、feature と route を import しない。
置き場所の決定理由は [ADR-032](../../adr/ADR-032-organize-frontend-by-business-feature.md) に示す。

## 定義

画面には、どの業務の画面にも共通する枠と、業務によらない状態の表示がある。
**アプリの共有component**は、それらを描画する component であり、アプリの中の複数の route か feature から使う。
今のファイルと役割は次のとおりである。

- **`app-shell.tsx`**：header、ログアウトの form、`<Outlet />` を持つアプリシェル。`routes/_authenticated/route.tsx` の component にする（[認証が要る画面とアプリシェル](../routing-and-state.md#認証が要る画面とアプリシェル)）。
- **`route-pending.tsx`、`route-error.tsx`、`route-not-found.tsx`**：router の既定の Loading、Error、存在しない resource の表示。`router-defaults.ts` の `routerDefaults` に登録する（[状態表示の分担](../routing-and-state.md#状態表示の分担)）。
- **`problem-notice.tsx`**：[lib](lib.md) の `notice.ts` が作った `Notice` を live region に出す通知。
- **`status-screen.tsx`**：アプリシェルの外で、見出し、説明、もう一度ログインするリンクを出す画面。リンクはページ全体を遷移して `@frontend/api` の `loginPath` を開く。`/logged-out`、`/login-failed`、`/session-expired` の route の component が使う。

アプリの共有componentは [UI primitive](ui-primitive.md) ではない。
UI primitive は catalog の文言、router、API の型を扱わず、`packages/ui` に置く。
また、特定の feature のデータとロジックを持つ業務component でもない。
業務component は、その feature の `components/` に置く（[共通componentと業務component](../component-design.md#共通componentと業務component)）。

feature の中のコードを components へ移す条件は[共有コード](../architecture.md#共有コード)に従う。
二つ以上の feature で同じ責務が確認できるまでは、feature の中に置く。

## 置き場所と命名

- `frontend/apps/web/src/components/` の直下に置く。
- ファイル名は component 名を kebab-case にする（`AppShell` は `app-shell.tsx`）。
- router の既定の表示は、component 名を `Route` に状態の名前を付けた形にし（`RoutePending`、`RouteError`、`RouteNotFound`）、ファイル名を `route-<状態>.tsx` にする。
- 1 ファイルに component を 1 つだけ置き、component 以外を export しない。
- component は関数宣言で書き、名前付きで export する（`export function AppShell()`）。

命名の全体は [componentの命名と設計](../component-design.md#命名) に従う。

## 必須の記述

- 利用者向けの文言は `useTranslation` の `t` で catalog から出し、JSX に文字列を直接書かない（[フロントエンドの国際化](../i18n.md)）。
- エラーの表示に `error.message` と Problem Details の `detail` を出さず、catalog の文言を出す。
  backend の `title` を出すときは、見出しの要素にそのまま出し、catalog の文言と連結しない（[分岐と文言](../update-conflicts.md#分岐と文言)）。
- `RouteError` の再試行は、`useQueryErrorResetBoundary` の `reset()` を呼んでから `router.invalidate()` を呼ぶ。
  テナントを選ぶ前の 403 は `isTenantNotSelected` で判定し、再試行の代わりにテナントを選ぶ画面へのリンクを出す。
- 状態の変化を読み上げる領域は `<output>` にする（[UIの書き方](../ui-and-style.md#uiの書き方)）。
  `ProblemNotice` は、通知がない間も `<output>` を描画しておき、中身だけを入れ替える。
- button の見た目の link は `<Link className={buttonVariants(...)}>` で書く。
- アプリシェルは `<Outlet />` を描画する。
- Effect を使わない。
  描画する要素に結び付く処理は ref callback で書き、後始末の関数を返す（`AppShell` の `watchPageShow`。[Effectの禁止](../routing-and-state.md#effectの禁止)）。
- router の既定の表示は `routerDefaults` に登録し、`main.tsx` と route のテストが同じ値で router を作る。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@frontend/ui` の部品、`@/lib` の関数と型、react-i18next の `useTranslation`、TanStack Router と TanStack Query の Hook と component。
- **依存してはいけないもの**：`@/features/**`、`@/routes/**`、`@/routeTree.gen`、`@base-ui/**`、テスト用部品（`msw`、`@frontend/api/mocks/**`、`@/testing/**`、`@testing-library/**`）、`@grafana/*`、global の `fetch` と `XMLHttpRequest`。

テレメトリは `@/lib/telemetry` の関数を通して呼ぶ（`AppShell` の `resetTelemetrySession`）。
ファイルの範囲ごとの禁止の一覧は[ファイルの範囲ごとの制限](../lint-config.md#ファイルの範囲ごとの制限)にある。
テストファイルと `src/testing/` では、テスト用部品と層の import の禁止が外れる。

`components` が参照できる `@frontend/api` の入口は、`api-error` と `csrf` だけである（[依存方向](../architecture.md#依存方向)）。
`api-fetch`、`endpoints`、`models`、`zod` は lint が止める。
今の `app-shell.tsx` は `@frontend/api/csrf` を、`route-error.tsx` は `@frontend/api/api-error` を import している。

## 最小の例と典型的な例

最小の例は、読み込み中の表示の `RoutePending` である（`frontend/apps/web/src/components/route-pending.tsx`）。

```tsx
import { useTranslation } from "react-i18next";

export function RoutePending() {
  const { t } = useTranslation();

  return (
    <output className="block px-6 py-16 text-muted-foreground sm:px-10">{t("loading")}</output>
  );
}
```

典型的な例は、エラーの表示の `RouteError` である（`frontend/apps/web/src/components/route-error.tsx`）。
`error.message` を出さず、テナントを選ぶ前の 403 だけはテナントを選ぶ画面へ案内する。

```tsx
import { isTenantNotSelected } from "@frontend/api/api-error";
import { Button, buttonVariants } from "@frontend/ui/button";
import { useQueryErrorResetBoundary } from "@tanstack/react-query";
import { Link, useRouter } from "@tanstack/react-router";
import type { ErrorComponentProps } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

// error.message は内部情報を含みうるため表示せず、カタログの文言だけを出す。
export function RouteError({ error }: ErrorComponentProps) {
  const { t } = useTranslation();
  const { reset } = useQueryErrorResetBoundary();
  const router = useRouter();

  // 所属を外されたときなどは、再試行しても同じ 403 になるため、テナントを選び直す画面へ案内する。
  if (isTenantNotSelected(error)) {
    return (
      <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10" role="alert">
        <h1 className="text-2xl font-semibold">{error.problem?.title ?? t("errorHeading")}</h1>
        <p className="text-muted-foreground">{t("tenancy.notSelectedDescription")}</p>
        <Link to="/tenants" className={buttonVariants()}>
          {t("tenancy.chooseTenant")}
        </Link>
      </section>
    );
  }

  return (
    <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10" role="alert">
      <h1 className="text-2xl font-semibold">{t("errorHeading")}</h1>
      <p className="text-muted-foreground">{t("errorDescription")}</p>
      <Button
        type="button"
        onClick={() => {
          reset();
          // loader の失敗は match の error 状態になり、invalidate の Promise は reject しない。
          void router.invalidate();
        }}
      >
        {t("retry")}
      </Button>
    </section>
  );
}
```

通知の `ProblemNotice` は、見出しと説明を別の要素に分ける（`frontend/apps/web/src/components/problem-notice.tsx`）。

```tsx
import type { Notice } from "@/lib/notice";

/** 常に描画する live region。output の内容は phrasing content なので、見出しは strong で表す。見出しと説明を別の要素に分け、1 つの文字列に連結しない。 */
export function ProblemNotice({ notice }: { notice: Notice | undefined }) {
  return (
    <output className="block space-y-1">
      {notice === undefined ? undefined : (
        <>
          <strong className="block">{notice.heading}</strong>
          {notice.description === undefined ? undefined : (
            <span className="block text-muted-foreground">{notice.description}</span>
          )}
          {notice.details?.map((detail) => (
            <span key={detail} className="block text-sm">
              {detail}
            </span>
          ))}
        </>
      )}
    </output>
  );
}
```

## 対応するテスト

jsdom の component test で、role と label から要素を探し、利用者から見える結果を確かめる（[フロントエンドのテストと検証](../testing.md)）。

- router の既定の表示は、`frontend/apps/web/src/router-defaults.test.tsx` が `routerDefaults` で router を作って確かめる。
  not found の見出しとリンク、loader の失敗で詳細を出さないことと再試行、読み込み中の `status` を見る。
- アプリシェルは、`frontend/apps/web/src/components/app-shell.test.tsx` が `renderRoute("/")` で描画し、ログアウトの form の送信を確かめる。
- `StatusScreen` は自身のテストファイルを持たず、使う route のテスト（`routes/-logged-out.test.tsx`、`routes/-login-failed.test.tsx`、`routes/-session-expired.test.tsx`）が見出し、リンクの `href`、`<title>` を確かめる。
- `ProblemNotice` は自身のテストファイルを持たず、使う画面のテスト（`features/orders/pages/order-new-page.test.tsx` など）が `getByRole("status")` で通知を読む。

次は `frontend/apps/web/src/router-defaults.test.tsx` の not found のテストである。

```tsx
  it("存在しない path では、言語に合わせた not found の表示と、ホームへ戻るリンクを出す", async () => {
    renderRouter(routeTree, "/no-such-page");

    await expect(screen.findByRole("heading", { name: "Page not found" })).resolves.toBeTruthy();
    expect(screen.getByRole("link", { name: "Back to home" }).getAttribute("href")).toBe("/");
  });
```

## アンチパターン

- 一つの feature だけが使う component を、将来の再利用を見込んで components に置く。
- feature の業務語彙やデータを持つ component を components に置く。
- `error.message` や Problem Details の `detail` を画面に出す。
  内部情報が利用者に見える。
- backend の `title` と catalog の文言を 1 つの文字列に連結する。
- 通知の `<output>` を、通知があるときだけ描画する。
  支援技術は既にある live region の中身の変化を読み上げるため、中身と同時に作った領域は読み上げられないことがある。
- `<Link>` の中に `<Button>` を置く、または `Button` の `render` に link を渡す。
- `useEffect` で `pageshow` などの event を購読する。

## 作成時のチェックリスト

- [ ] 二つ以上の feature か route で使い、特定の feature の業務語彙に属さない。［自分で点検］
- [ ] `apps/web/src/components/` の直下に置く。［自分で点検］
- [ ] ファイル名を component 名の kebab-case にする。［Oxlint で検査：unicorn/filename-case］
- [ ] 1 ファイルに component を 1 つだけ置き、component 以外を export しない。［Oxlint で検査：react/no-multi-comp、react/only-export-components］
- [ ] component を関数宣言で書く。［Oxlint で検査：func-style、react/prefer-function-component］
- [ ] feature、route、route tree、Base UI、テスト用部品、テレメトリ SDK を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] `@frontend/api` は `api-error` と `csrf` だけを import し、`api-fetch`、`endpoints`、`models`、`zod` を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] `fetch` と `XMLHttpRequest` を使わない。［Oxlint で検査：no-restricted-globals、no-restricted-properties］
- [ ] 文言を JSX に直接書かず、`t` で catalog から出す。［JSX の子は Oxlint で検査：react/jsx-no-literals。属性の文言は自分で点検］
- [ ] `error.message` と `detail` を表示しない。［自分で点検］
- [ ] backend の `title` は独立した要素に出し、catalog の文言と連結しない。［自分で点検］
- [ ] 状態の変化を読み上げる領域を `<output>` にする。［Oxlint で検査：jsx-a11y/prefer-tag-over-role（`role="status"` を書いた場合だけ。領域の要否は自分で点検）］
- [ ] 通知の `<output>` を、通知がない間も描画する。［自分で点検］
- [ ] button の見た目の link を `buttonVariants` で書く。［Oxlint で検査：ui-composition/no-button-in-link］
- [ ] `@frontend/ui` の部品の見た目の class を `className` で上書きしない。［Oxlint で検査：shadcn/no-restyle］
- [ ] 値から Tailwind の class 名を組み立てない。［Oxlint で検査：tailwind-classes/no-dynamic-class-name］
- [ ] Effect を使わず、要素に結び付く処理を ref callback で書く。［Oxlint で検査：react-rules/no-effect］
- [ ] `RouteError` の再試行は `reset()` の後に `router.invalidate()` を呼ぶ。［自分で点検］
- [ ] router の既定の表示を `routerDefaults` に登録する。［自分で点検］
- [ ] 利用者から見える結果を jsdom の component test で確かめる。［自分で点検］
