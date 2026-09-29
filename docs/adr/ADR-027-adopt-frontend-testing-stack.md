# ADR-027: Frontendのテスト基盤を標準化する

## Status

Proposed

## Date

2026-09-29

## Context

FrontendはVite+経由のVitestで純粋関数とReactのserver renderingを検証している。
現在のテストはNode環境で動き、componentをDOMへmountした利用者操作、API境界、coverageを扱う直接依存を持たない。

React、Base UI、TanStack Router、TanStack Query、TanStack Formを使う画面では、roleやlabelを基準にcomponentを操作し、click、keyboard入力、focus、非同期状態を検証する必要がある。
Node環境だけでは `window` と `document` がないため、通常のcomponent testにはDOM実装も必要になる。

Spring Boot APIのclientはOrvalで生成する方針である。
APIを使うcomponent testでは、生成関数そのものをmockするよりHTTP境界をmockした方が、requestとresponseの扱いを実装に近い経路で検証できる。

## Decision

テストrunnerとassertionにはVite+同梱のVitestを使い続け、Vitestを重複して直接追加しない。
React component testにTesting Library React、Testing Library DOM、user-event、jsdomを使う。

DOMを必要とするテストだけをjsdom環境で実行し、純粋関数とserver renderingのテストはNode環境を維持する。
実ブラウザのlayout、focus、portal、pointer操作が必要になった場合はVitest Browser ModeまたはPlaywrightを別途判断する。

API component testにはMSWを使う。
MSWのpostinstall scriptは実行機能に不要なため、pnpmの `allowBuilds` で無効化する。
最初のAPIテストでserver lifecycleと未処理requestを失敗させる設定を追加し、Orvalが生成するhandlerを利用できる場合は手書きhandlerとの重複を避ける。

coverage providerにはVitestと同じ版の `@vitest/coverage-v8` を使う。
起動処理の `src/main.tsx`、test、型宣言、自動生成された `src/routeTree.gen.ts` を除く手書きproduction source全体を対象にする。
バックエンドのJaCoCo基準と同じ意味に揃え、ファイル単位ではなく全体のbranch coverage 85%をローカルとCIで強制する。
最初のOrval生成先を確定したときは、その生成ディレクトリだけをcoverage対象から除外する。

## Consequences

### Positive

- componentをroleとlabelから操作し、利用者から見える挙動を検証できる。
- user-eventによってclick、keyboard、focusをブラウザ操作に近いevent順で再現できる。
- MSWによってTanStack Queryのloading、success、errorをHTTP境界で検証できる。
- V8 coverageをVitestと同じversionで実行できる。

### Negative

- jsdom、MSW、coverage providerの依存と更新作業が増える。
- jsdomはlayoutと完全なブラウザ挙動を再現しないため、実ブラウザが必要な不具合は検出できない。
- coverage数値はテストの検出力を直接保証しない。

### Neutral

- 依存は先に固定するが、空のsetup fileとMSW serverは作らない。
- coverageは全体branch 85%だけを強制し、statements、functions、linesとファイル単位の閾値は設定しない。
- jest-dom、Playwright、axe、fast-checkは利用する要件が生じるまで追加しない。

## Alternatives Considered

### Vitest Browser Modeをすべてのcomponent testに使う

- **Description**：jsdomを使わず、全component testを実ブラウザで実行する。
- **Pros**：browser API、focus、layoutの再現性が高い。
- **Cons**：browser providerとbinaryの導入、CI起動時間、test projectの分離が必要になり、現在の通常component testには重い。

### React componentをserver renderingだけで検証する

- **Description**：現在の `renderToStaticMarkup` による文字列検査を続ける。
- **Pros**：DOM環境と操作ライブラリを追加せず高速に実行できる。
- **Cons**：state更新、event、focus、フォーム入力を検証できない。

### API関数を直接mockする

- **Description**：MSWを使わず、Orval生成関数やmoduleをmockする。
- **Pros**：HTTP mock serverのsetupが不要になる。
- **Cons**：request生成とresponse parsingを迂回し、実際のHTTP境界から離れたテストになる。

## References

- [Testing Library: React](https://testing-library.com/docs/react-testing-library/intro/)
- [Testing Library: user-event](https://testing-library.com/docs/user-event/intro/)
- [Vitest: Test environment](https://vitest.dev/guide/environment.html)
- [Vitest: Coverage](https://vitest.dev/guide/coverage.html)
- [MSW](https://mswjs.io/docs/)
- [ADR-024: Frontend API client生成にOrvalを採用する](./ADR-024-adopt-orval-for-frontend-api-client.md)
