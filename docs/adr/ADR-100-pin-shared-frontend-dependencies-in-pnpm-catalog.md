---
type: ADR
title: 'ADR-100: 2 つ以上の package が使う frontend の依存は pnpm の catalog で版を 1 つにする'
description: 'frontend の workspace で 2 つ以上の package.json に現れる依存は、pnpm-workspace.yaml の catalog に版を置き、各 package.json は catalog: で参照すると決める。'
tags: [adr, frontend, dependencies, supply-chain]
---

# ADR-100: 2 つ以上の package が使う frontend の依存は pnpm の catalog で版を 1 つにする

## Status

Proposed

## Date

2026-11-19

## Context

[ADR-092](ADR-092-split-frontend-into-vite-plus-workspace.md) は frontend を pnpm の workspace に分け、catalog を Vite+ の版の固定にだけ使った。
ほかの依存は、使う package ごとの `package.json` に同じ版を書いていた。
zod、react、`@tanstack/react-query`、msw など 10 個の依存は、2 つか 3 つの `package.json` に同じ版で現れる。

zod の脆弱性（[GHSA-mrhx-c7xc-m3w5](https://github.com/advisories/GHSA-mrhx-c7xc-m3w5)）の修正で、次のことが起きた。

- Dependabot のセキュリティ更新は、alert が指す `package.json` のディレクトリごとに動く。zod の alert は `frontend/apps/web/package.json` と `frontend/packages/api/package.json` を指していた。
- #479 は `apps/web` の zod だけを上げた。マージしていたら `packages/api` の zod が古いまま残り、版が割れていた。#479 は閉じて #488 に置き換わった。
- #488 は 2 つの `package.json` を上げたが、`frontend/pnpm-lock.yaml` を更新しなかった。CI の `pnpm install --frozen-lockfile` が `ERR_PNPM_OUTDATED_LOCKFILE` で落ち、手で `task fe-install` を実行して lockfile を直した。
- `apps/web` は `@frontend/api` の生成した zod の schema と、自分の `zod` を組み合わせる。版が割れると zod が 2 つ入り、schema の型が合わなくなる。
- `packages/ui` が使う shadcn 4.21.0 は zod 3.25.76 に依存していた。zod 3 には修正版がなく、alert #20 が開いたまま残った。shadcn は 4.22.0 から zod ^4.2.0 に依存する。

Dependabot は 2025 年 2 月から pnpm の catalog を扱う（[GitHub Changelog](https://github.blog/changelog/2025-02-04-dependabot-now-supports-pnpm-workspace-catalogs-ga/)）。
セキュリティ更新が catalog の版と lockfile を一緒に更新するかは、まだ確かめていない。

## Decision

frontend の workspace で 2 つ以上の `package.json`（ルートの `frontend/package.json` を含む）に現れる依存は、`frontend/pnpm-workspace.yaml` の `catalog:` に版を置く。
各 `package.json` は `"catalog:"` で参照し、版を書かない。
`workspace:` で参照する workspace の package は対象外にする。

- 名前付きの catalog（`catalog:<名前>`）は使わない。名前ごとに別の版を置けるので、版が 1 つにならない。
- catalog へ移すときは、`package.json` に書いていた版の指定をそのまま移す。完全な固定はそのままにし、`typescript` の `~7.0.2` も範囲のまま移す。
- `frontend/lint/workspace-layout.test.js` が、2 つ以上の `package.json` に `catalog:` 以外で書いた依存を見つけて失敗する。

## Consequences

### Positive

- 共有する依存の版が 1 か所になり、片方の package だけが上がって版が割れることがなくなる。
- 版の置き場が lockfile と同じ `frontend/` になる。Dependabot が catalog を更新するなら、lockfile も一緒に更新される見込みがある。
- 検査が誤りを止めるので、人やエージェントが規則を覚えていなくても版が割れない。

### Negative

- 依存を上げるときに、`package.json` ではなく `pnpm-workspace.yaml` を見る必要がある。
- 1 つの package だけで使う依存を 2 つ目の package が使い始めたら、版を catalog へ移す手間がかかる。検査が落ちて知らせる。
- Dependabot のセキュリティ更新が lockfile を更新しない問題は、この判断で直るとは限らない。直らなければ、手で lockfile を直す運用が続く。

### Neutral

- 次の frontend の alert で、Dependabot のセキュリティ更新が catalog と lockfile を更新するかを確かめる。更新しなければ、lockfile を直す workflow を別に検討する。それまでは、lockfile のない更新を手で直す（[開発ワークフロー](../tooling/dev-workflow.md)）。CI の `pnpm install --frozen-lockfile` が落ちるので、そのような更新は auto-merge されない。
- この判断と同じ変更で shadcn を 4.23.0 に上げ、lockfile から zod 3 をなくす。

## Alternatives Considered

### 選択肢1: package ごとに版を書き続け、レビューで揃える

- **Description**：今のまま各 `package.json` に版を書き、Dependabot の Pull Request をレビューで確かめる。
- **Pros**：設定を変えない。
- **Cons**：#479 のように片方だけが上がる更新を、人が毎回見つける必要がある。auto-merge される patch の更新では見落とす。

### 選択肢2: すべての依存を catalog に置く

- **Description**：1 つの package だけが使う依存も catalog に置き、`package.json` には `catalog:` と `workspace:` だけを書く。`catalogMode: strict` で `pnpm add` にも catalog を使わせる。
- **Pros**：検査が「`catalog:` か `workspace:` だけ」と単純になる。2 つ目の package が使い始めたときに版を移す手間がない。
- **Cons**：どの package も `package.json` で版を読めなくなる。版の割れが起きうるのは 2 つ以上の package が使う依存だけなので、それ以外まで移す理由がない。

### 選択肢3: Dependabot の Pull Request で lockfile を作り直して push する workflow を加える

- **Description**：Dependabot の branch で `pnpm install --lockfile-only` を実行し、差分を push する。
- **Pros**：Dependabot が lockfile を更新しなくても、手を動かさずに直る。
- **Cons**：Dependabot の Pull Request に書き込める token が要る。`GITHUB_TOKEN` の push は CI を起動しないので、GitHub App か PAT を足すことになる。版が割れる問題は直らない。

### 選択肢4: zod 3 の alert を dismiss する

- **Description**：shadcn は `@import "shadcn/tailwind.css"` だけで使い、zod 3 はブラウザの bundle に入らないので、理由を書いて alert #20 を閉じる。
- **Pros**：依存を変えない。
- **Cons**：shadcn を上げれば zod 3 がなくなるので、dismiss の理由を管理し続ける必要がない。

## References

- [ADR-092: frontend を Vite+ の workspace に分け、共有を packages/ui と packages/api に置く](ADR-092-split-frontend-into-vite-plus-workspace.md)
- [pnpm の Catalogs](https://pnpm.io/catalogs)
- #479、#488
