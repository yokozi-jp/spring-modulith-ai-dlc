---
type: Convention
title: ファイルの役割：lib
description: frontend/apps/web/src/lib に置く、component を持たない feature 非依存の関数と型（操作の結果の通知、テレメトリ）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。複数の feature が使う関数を lib に置くか決めるとき、通知やテレメトリの関数を変えるときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：lib

lib は、アプリの依存の最下層に置く、component を持たない feature 非依存の関数と型であり、`frontend/apps/web/src/lib/` に置く。
lib は components、feature、route を import せず、テレメトリの SDK は `lib/telemetry.ts` だけが呼ぶ。
今あるのは、操作の結果の通知を作る `notice.ts` と、テレメトリの `telemetry.ts` と `telemetry-sanitize.ts` である。
置き場所の決定理由は [ADR-032](../../adr/ADR-032-organize-frontend-by-business-feature.md) に、テレメトリの決定は [ADR-068](../../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md) に示す。

## 定義

feature によらず使う処理には、描画を持たないものがある。
**lib** は、その処理を React の state を使わない通常の関数と型にして置く場所である。
今のファイルと役割は次のとおりである。

- **`notice.ts`**：画面の live region に出す通知の型 `Notice` と、`ApiProblemError` から通知を作る `failureNotice`、`problemNotice`、`problemStatus`。
  複数の feature が使い、[アプリの共有component](shared-component.md)の `ProblemNotice` が描画する。
- **`telemetry.ts`**：Faro Web SDK を呼ぶ唯一のファイル。
  初期化の `initTelemetry`、画面遷移の `bindRouterTelemetry`、例外の `reportCaughtError`、ログアウトの前の `resetTelemetrySession` を持つ。
- **`telemetry-sanitize.ts`**：SDK に依存しない `beforeSend` の縮約。
  `telemetry.ts` だけが呼ぶ。

lib はアプリの共有componentではない。
描画は components に置き、lib は components を参照しない。
特定の feature の業務語彙に属す関数は、その feature に置く（`features/orders/notice.ts` の `formFailure`）。
feature の中のコードを lib へ移す条件は[共有コード](../architecture.md#共有コード)に従う。

## 置き場所と命名

- `frontend/apps/web/src/lib/` の直下に置き、ファイル名を kebab-case にする。
  今のファイル名は用途の名前である（`notice.ts`、`telemetry.ts`）。
- テストは対象と同じディレクトリに `<名前>.test.ts` で置く。
- テレメトリでは、SDK に依存しない処理を SDK を呼ぶファイルと分ける（`telemetry-sanitize.ts`）。
  理由は [ADR-068](../../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md) に示す。

## 必須の記述

- React の state を使わない通常の関数にし、関数宣言で書く（[React Hooksと状態](../routing-and-state.md#react-hooksと状態)）。
  catalog の文言が要る関数は、i18next の `TFunction` の `t` を引数で受け取る（`failureNotice(t, error, descriptions)`）。
- 通知に `error.message` と Problem Details の `detail` を入れない。
  backend の `title` を見出しに、操作ごとの catalog の文を説明にし、2 つを連結しない（[分岐と文言](../update-conflicts.md#分岐と文言)）。
- 分岐は `ApiProblemError` の `status` と `problem.type` で行い、`title` と `detail` の文字列で分岐しない。
- テレメトリの SDK は、ビルド時の定数 `__TELEMETRY_ENABLED__` が真のときだけ動的 import で読み込む。
  既定の無効のビルドには SDK が入らない。
- テレメトリの送信と初期化の失敗で、描画、画面遷移、ログアウトを止めない。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：同じ lib のファイル、ブラウザの標準の API、型としての i18next の `TFunction`。
  `lib/telemetry.ts` だけは `@grafana/faro-web-sdk` に依存してよい。
- **依存してはいけないもの**：`@/components/**`、`@/features/**`、`@/routes/**`、`@/routeTree.gen`、`@base-ui/**`、テスト用部品、`lib/telemetry.ts` の外からの `@grafana/*`、global の `fetch` と `XMLHttpRequest`。

ファイルの範囲ごとの禁止の一覧は[ファイルの範囲ごとの制限](../lint-config.md#ファイルの範囲ごとの制限)にある。
テストファイルと `src/testing/` では、テスト用部品と層の import の禁止が外れる。

`notice.ts` は `@frontend/api/api-error` の `ApiProblemError` を import している。
[依存方向](../architecture.md#依存方向)は lib から `@frontend/api` への向きを図に定めておらず、lint はこの向きを止めていない。

## 最小の例と典型的な例

最小の例は、失敗の status を取り出す `problemStatus` である（`frontend/apps/web/src/lib/notice.ts`）。

```ts
export function problemStatus(error: unknown): number | undefined {
  return error instanceof ApiProblemError ? error.status : undefined;
}
```

典型的な例は、操作の失敗を通知にする `failureNotice` である（`frontend/apps/web/src/lib/notice.ts`）。
404、409、422 は backend の `title` と操作ごとの説明を、それ以外は一般の文言だけを返す。

```ts
/**
 * 404 と 409 と 422 は操作ごとの説明を、それ以外は一般の失敗を通知にする。
 * 入力検証の 400 は扱わないので、form のある画面では formFailure を使う。
 * conflict は省略できない。409 を呼び出し側で先に扱う場合だけ、明示して undefined を渡す。
 * 404 を操作の失敗として扱う画面だけ、notFound を渡す。
 */
export function failureNotice(
  t: TFunction,
  error: unknown,
  descriptions: { conflict: string | undefined; unprocessable: string; notFound?: string },
): Notice {
  const status = problemStatus(error);
  if (status === 404 && descriptions.notFound !== undefined) {
    return problemNotice(t, error, descriptions.notFound);
  }
  if (status === 409 && descriptions.conflict !== undefined) {
    return problemNotice(t, error, descriptions.conflict);
  }
  if (status === 422) {
    return problemNotice(t, error, descriptions.unprocessable);
  }
  // error.message と detail は内部情報を含みうるため出さない。
  return {
    heading: t("notice.generalTitle"),
    description: t("notice.generalFailure"),
  };
}
```

テレメトリの関数は、SDK を待つ間も呼び出し側を止めない（`frontend/apps/web/src/lib/telemetry.ts`）。

```ts
export function reportCaughtError(error: unknown): void {
  if (!(error instanceof Error) || reported.has(error) || sendingSuppressed) {
    return;
  }
  reported.add(error);
  void (async () => {
    const faro = await faroReady;
    faro?.api.pushError(error);
  })();
}
```

## 対応するテスト

Node 環境の unit test を対象と同じディレクトリに置く（[観点ごとのテスト](../test-strategy.md#観点ごとのテスト)）。

- catalog の文言が要るテストは、`src/testing/catalog-i18n.ts` の `t` を使う。
  `@/i18n` は `document` を使うため、Node のテストでは catalog だけを読んだ instance を使う。
- `notice.test.ts` は、status ごとに通知の見出しと説明を確かめ、500 の `detail` を出さないことと、`conflict` の渡し忘れが型エラーになること（`@ts-expect-error`）も確かめる。
- テレメトリのテストは、`replaceTelemetryForTesting` で SDK の送り先を差し替え、`vi.mock` を使わない。

次は `frontend/apps/web/src/lib/notice.test.ts` の 500 のテストである。

```ts
  it("サーバーの内部の失敗は、サーバーが返した見出しと詳細を出さず、一般の見出しと説明だけを出す", () => {
    const error = new ApiProblemError(500, {
      type: "about:blank",
      title: "Internal Server Error",
      status: 500,
      detail: "SQLException: relation orders",
    });

    expect(failureNotice(t, error, descriptions)).toStrictEqual(general);
  });
```

## アンチパターン

- 一つの feature だけが使う関数や、feature の業務語彙に属す関数を lib に置く。
- lib に component を置く、または components を import する。
- 通知に `error.message` か `detail` を入れる。
  内部情報が利用者に見える。
- `title` と catalog の文言を 1 つの文字列に連結する。
- `title` や `detail` の文字列で分岐する。
- feature、route、component から `@grafana/*` を呼ぶ。
- テレメトリの失敗を呼び出し側へ投げ、画面遷移やログアウトを止める。

## 作成時のチェックリスト

- [ ] 特定の feature の業務語彙に属さない。feature 以外（`main.tsx`、`components/`）だけが使うときも、描画を持たなければ lib に置く。［自分で点検］
- [ ] component を置かず、React の state を使わない通常の関数にする。［自分で点検］
- [ ] ファイル名を kebab-case にする。［Oxlint で検査：unicorn/filename-case］
- [ ] 関数を関数宣言で書く。［Oxlint で検査：func-style］
- [ ] components、feature、route、route tree、Base UI、テスト用部品を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] `@grafana/*` を `lib/telemetry.ts` の外で import しない。［Oxlint で検査：no-restricted-imports］
- [ ] `fetch` と `XMLHttpRequest` を使わない。［Oxlint で検査：no-restricted-globals、no-restricted-properties］
- [ ] 通知に `error.message` と `detail` を入れない。［自分で点検］
- [ ] `title` と catalog の文言を連結せず、分岐を `status` と `problem.type` で行う。［自分で点検］
- [ ] テレメトリの SDK は、ビルド時の定数が真のときだけ動的 import で読み込む。［自分で点検］
- [ ] テレメトリの失敗で、描画、画面遷移、ログアウトを止めない。［自分で点検］
- [ ] Node 環境の unit test を対象と同じディレクトリに置く。［自分で点検］
- [ ] テストで `vi.mock` と `vi.doMock` を使わない。［Oxlint で検査：vitest/no-restricted-vi-methods］
