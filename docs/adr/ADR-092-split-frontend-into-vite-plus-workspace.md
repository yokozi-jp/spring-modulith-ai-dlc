---
type: ADR
title: 'ADR-092: frontend を Vite+ の workspace に分け、共有を packages/ui と packages/api に置く'
description: frontend を、apps/web と 2 つ目のアプリ、共有の packages/ui と packages/api からなる workspace にする。lint と fmt はルートの 1 か所に集め、共有 package は src を直接公開し、run.cache は有効にしない。
tags: [adr, frontend, vite-plus, monorepo]
---

# ADR-092: frontend を Vite+ の workspace に分け、共有を packages/ui と packages/api に置く

## Status

Proposed

## Date

2026-11-18

## Context

frontend は、アプリ 2 つと共有 package からなる構成になる見込みである。
共有するのは UI 部品と、バックエンドとのつなぎ部分（Orval の生成物、`api-fetch`、MSW の mock）である。
今の JS の package は `frontend/` の 1 つだけなので、Vite+ の設定をルートへ集める利得がなく、#260 は見送った。
2 つ目のアプリを作る前に、workspace の形を決める。
形はあとから戻しにくく、設定、CI、E2E、リリースの参照先に広がる。

Vite+ の公式ドキュメントによると、`vp check` はルートの `lint` と `fmt` の設定を使い、package 側の設定で置き換えない。
package ごとの規則は、ルートの `lint.overrides` に書く。

Issue #419 の問いのうち、次の 3 点は、Vite+ 0.3.3 の scratch workspace（アプリ 2、ui 1）で実測して確かめた。

- **設定の優先**：package の `vite.config.ts` に `lint` があっても、`vp check` はルートの設定を使う。
  `vp lint` を package で単体実行すると、package の設定が使われ、ルートの規則が黙って外れる。
  package に `lint` がなければ、ルートの設定が使われる。
- **アプリごとの CSS と shadcn**：`better-tailwindcss` の `entryPoint` は、override の規則の option で渡せる。
  ただし相対パスは cwd 基準であり、`vp run -r` は各 package の dir で走るため `No tailwind css entry point found` になる。
  `import.meta.dirname` から作った絶対パスなら、どの cwd でも解決する。
  `@shadcn/lint` は、ui を別 package に置いても部品を認識する。
  各アプリの `components.json`（`aliases.ui: "@scope/ui"`）と、ルートの override に渡した `componentImports` のどちらでも、`no-restyle` が `@scope/ui/button` への `className` を検出した。
- **`run.cache`**：既定は `{ scripts: false, tasks: true }` で、package.json の script は cache されない。
  `vp create vite:monorepo` の雛形が書く `run.cache: true` は、script の cache も有効にする。
  その状態で、`vp build` が `loadEnv` で読む `FRONTEND_OTEL_ENABLED` は自動で追跡され、値が変わると cache が外れた。
  一方、`vite.config.ts` が `process.env.FRONTEND_REACT_COMPILER` を直接読むと、その変数は task に渡らず（bundle には未設定の値が入った）、値を変えても cache が当たった。
  localhost の HTTP で状態を読む script（E2E の backend に当たる）は、状態を変えても前の出力が再生された。

## Decision

frontend を次の Vite+ workspace に分ける。

| 項目                  | 決定                                                                                                                                                                                            |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 構成                  | `frontend/apps/web`、2 つ目のアプリ（`frontend/apps/<名前>`）、`frontend/packages/ui`（`@frontend/ui`）、`frontend/packages/api`（`@frontend/api`）の 4 package                                 |
| workspace のルート    | `frontend/`。リポジトリ直下には置かない                                                                                                                                                         |
| lint と fmt           | ルートの `frontend/vite.config.ts` に `lint` と `fmt` だけを置く。アプリごとの差は override で書く。package の `vite.config.ts` に `lint` か `fmt` を書かせない                                 |
| 実行の入口            | `task fe-check` などは、常にルートで `vp check` を実行する                                                                                                                                      |
| 共有 package の配り方 | `exports` で `src` を直接公開し、ビルドしない。`ui` は部品ごとの `"./*": "./src/*.tsx"`。`api` は `endpoints`、`models`、`mocks`、`api-fetch` を個別に公開する。barrel の `index.ts` は作らない |
| `run.cache`           | 設定しない（既定のまま、script は cache しない）。cache が要る command は、後続の判断で `run.tasks` に宣言する                                                                                  |
| Vite+ の版            | 0.3.3 のまま catalog で固定し、移行と同時に上げない。`catalogMode: prefer` を採用する                                                                                                           |

`cn` は `ui` に、`csrf` は `api` に置く。
`i18n`、`telemetry`、`router-defaults`、`testing` はアプリに残す。
2 つ目のアプリが同じものを必要とすると確かめた時点で切り出す。

アプリごとの CSS は、ルートの override の `better-tailwindcss/*` の option に、`import.meta.dirname` から作った絶対パスの `entryPoint` を渡して指す。
今の `settings.better-tailwindcss.entryPoint: "src/style.css"` は cwd 基準なので置き換える。
shadcn は、各 package の `components.json` を保ち、ルートの override で `componentImports: ["^@frontend/ui(/|$)"]` を渡す。

層の規則は次の分担にする。

| 規則                                                         | 守る仕組み                                                                    |
| ------------------------------------------------------------ | ----------------------------------------------------------------------------- |
| `api` と `ui` が互いを参照しない、アプリ同士が参照しない     | 各 `package.json` に依存を書かない。pnpm の厳格な解決が import を失敗にする   |
| 未使用の依存と未宣言の依存                                   | Knip の workspace 設定                                                        |
| feature 間の import 禁止、feature から route の参照禁止      | アプリごとの override（`apps/*/src/features/**`）                             |
| `@base-ui/**` は `ui` の中だけ                               | `apps/**` と `packages/api/**` で禁止し、`packages/ui/**` で許可する override |
| `fetch` と `XMLHttpRequest` は `api` の中だけ                | `packages/api/**` を除く全体で禁止する override                               |
| テスト専用 import（`msw`、`@testing-library/**` など）の制限 | override に残す                                                               |

周辺は次のように直す。

| 対象                                                  | 直し方                                                                                                                                                                                  |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lint-config.test.js`                                 | 複製する対象を、ルートの設定、`apps/web`、`packages/*` の fixture に広げる。アプリごとの `entryPoint` の解決と、package の `vite.config.ts` に `lint` と `fmt` がないことも test にする |
| `loadEnv(mode, "..")`、`../version.txt`、`../openapi` | アプリの `vite.config.ts` と `orval.config.ts` で、リポジトリ直下を指す `../../..` に直す。Orval の出力先は `packages/api` にする                                                       |
| `Taskfile.yml` の `cd frontend && vp ...`             | `dir: frontend` を保ち、ルートで `vp check` と `vp run -r`、`--filter` を使う                                                                                                           |
| CI、Docker、リリース                                  | パス条件の `frontend/**` は変わらない。ビルドの対象 package を明示する                                                                                                                  |
| E2E                                                   | `playwright.config.ts` をアプリへ移し、`task e2e` の実行先を `vp run` の対象指定に変える                                                                                                |

移行は、各手順が単体で `task fe-verify`（手順 1 は E2E も）を通る小さな Pull Request に分ける。

1. `frontend/src` などを `frontend/apps/web/` へ `git mv` し、ルートの `package.json` と `vite.config.ts` を置いてパスを直す。
2. `packages/api` を切り出す。
3. `packages/ui` を切り出す。
4. 層の規則を依存の宣言へ寄せ、package の設定に `lint` と `fmt` がない test を足す。
5. 2 つ目のアプリを足す。

実装は別の Issue に分ける。

## Consequences

### Positive

- `api` が `ui` を参照しない規則と、アプリ同士が参照しない規則を、override の文字列ではなく依存の宣言で守れる。
- lint と fmt の規則が 1 か所にあり、アプリが増えても設定がずれない。
- 共有 package をビルドしないので、ビルド順の管理と `dist` の更新忘れが生じない。
- `run.cache` を有効にしないので、E2E の再生や、環境変数による bundle の取り違えは起きない。

### Negative

- ルートの `vite.config.ts` の override が、アプリの数だけ増える。
- `fe-verify`、CI、E2E、Docker の参照先を直す移行の工数がかかる。
- `vp lint` を package で単体実行すると、ルートの規則が外れる。
  入口をルートの `vp check` に限り、test で `lint` と `fmt` を禁じて抑える。
- 共有 package のソースを、アプリの Vite がそのままコンパイルする。
  Tailwind v4 が workspace の外の `ui` のソースを自動では走査しない可能性があり、各アプリの `style.css` に `@source` が要るかもしれない。
  これは推測で、未確認である。
- Knip は、`exports` で公開した `ui` と `api` の未使用の export を検出しにくい見込みで、これも未確認である。

### Neutral

- `components.json` と `componentImports` の組み合わせは、別々には実測したが、併用した形は未確認である。
  手順 3 で確かめる。
- `run.cache` は、環境変数の追跡と、副作用のある script（E2E など）の除外を決めたうえで、別の Issue で検討する。
- `loadEnv` ではなく `process.env` を直接読む `FRONTEND_REACT_COMPILER` は、cache を有効にする前に `loadEnv` へ寄せるか、`cache.env` に宣言する。
- 2 つ目のアプリの名前は、手順 5 で決める。

## Alternatives Considered

### 選択肢1: 共有を 1 つの package にする（3 package）

- **Description**：UI とつなぎ部分を `packages/shared` に入れる。
- **Pros**：package が 1 つ減り、設定が少ない。
- **Cons**：`api` が UI を参照しない規則を override に残すことになる。
  また、OpenAPI のたびに再生成される `api` と、安定した `ui` が同じ package になる。

### 選択肢2: workspace のルートをリポジトリ直下にする

- **Description**：`vp create vite:monorepo` の既定と同じく、リポジトリ直下に `apps/*` と `packages/*` を置く。
- **Pros**：雛形の既定に沿う。
- **Cons**：直下の `package.json` は commitlint、jscpd、lefthook のための別物で、1 つの `node_modules` に混ざる。
  Gradle の backend と同居し、`Taskfile.yml` の `dir: frontend` も変わる。

### 選択肢3: 共有 package を `dist` へビルドして配る

- **Description**：`vp create vite:monorepo` の雛形の `packages/*` と同じく、`vp pack` でビルドして公開する。
- **Pros**：公開ライブラリと同じ形になる。
- **Cons**：消費者が workspace のアプリだけなので、ビルド順の管理と `dist` の更新忘れという費用だけが残る。

### 選択肢4: `run.cache: true` を有効にする

- **Description**：雛形の既定に合わせ、script の cache を有効にする。
- **Pros**：再実行の時間が減る。
- **Cons**：実測で、`process.env` を直接読む値が task に渡らないまま cache が当たること、外部の状態を読む script の出力が再生されることを確認した。
  E2E と環境変数による bundle の取り違えを、検出できない形で起こす。

### 選択肢5: 並行ディレクトリに新しい workspace を作り、切り替える

- **Description**：`frontend/` と別に新しい frontend を作り、完成後に切り替える。
- **Pros**：移行中も旧構成が残る。
- **Cons**：`Taskfile.yml`、CI、E2E、Docker、lefthook、jscpd の参照が 2 系統になり、lockfile も 2 本になる。
  切替の時点が一括の移行になる。

## References

- [Issue #419: frontend を Vite+ の workspace に分ける形を決める](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/419)
- [Issue #390: frontend を複数アプリと共有 package の pnpm workspace にする構成の問い](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/390)
- [Issue #260: Vite+ の設定の置き方と禁止の表の取り込み（見送り）](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/260)
- [ADR-032: フロントエンドを業務能力で整理する](ADR-032-organize-frontend-by-business-feature.md)
- [ADR-052: OpenAPI 契約をコミットし、生成した API client を検査する](ADR-052-commit-openapi-contract-and-check-generated-client.md)
- [ADR-068: ブラウザのテレメトリを Faro で集め、Collector 経由で送る](ADR-068-collect-browser-telemetry-with-faro-via-collector.md)
- [Vite+ Check](https://viteplus.dev/guide/check)、[Lint Config](https://viteplus.dev/config/lint)、[Run Config](https://viteplus.dev/config/run)
