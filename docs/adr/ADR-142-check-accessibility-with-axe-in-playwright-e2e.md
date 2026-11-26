---
type: ADR
title: 'ADR-142: アクセシビリティの違反を Playwright の E2E で axe に検査させる'
description: ADR-027 の「axe は利用する要件が生じるまで追加しない」を改め、@axe-core/playwright を Playwright の E2E だけに入れ、画面をライトとダークの配色で WCAG 2.2 の AA と best-practice の規則で検査する決定。jsdom と Storybook には入れない。
tags: [adr, frontend, testing, accessibility, e2e]
---

# ADR-142: アクセシビリティの違反を Playwright の E2E で axe に検査させる

## Status

Proposed

## Date

2026-11-26

## Context

[ADR-027](ADR-027-adopt-frontend-testing-stack.md) は、axe を「利用する要件が生じるまで追加しない」とした。
アクセシビリティの違反は、Oxlint の規則と、jsdom の component test の role と label の検査だけで止めていた。

[#335](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/335) の調査で、lint と jsdom のテストが通る `main` の画面に、`task e2e` と同じ compose-test の環境の Chromium で axe（`@axe-core/playwright` 4.14.0、axe-core 4.14.0）を流した。
実在の違反が 4 種見つかった（[#647](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/647)）。

- `color-contrast`：トップの `bg-muted` の上の `text-muted-foreground` が 4.34:1、destructive の Button が 4:1 で、WCAG の 4.5:1 に届かない。
- `aria-valid-attr-value`：入力欄の `aria-errormessage` が指す文言に、読み上げる手段がない。
- `landmark-one-main` と `region`：404 の画面に `<main>` がない。

`color-contrast` は、jsdom の axe では判定できず `incomplete` になる。
jsdom は layout と描画の色を計算しないためである。
`landmark-one-main` と `region` は、ページ全体を対象にする規則で、部品だけを描画する component test では意味を持たない。

比較の相手（`yokozi-jp/agent-software-factory`）は、Storybook の a11y addon で部品の story に axe を流す。
この repo は Storybook を入れていない（[#284](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/284)）。

## Decision

`@axe-core/playwright` を `frontend/apps/web` の devDependency に入れ、Playwright の E2E だけで axe を流す。
jsdom の component test と Storybook には入れない。

- 検査の規則は、タグ `wcag2a`、`wcag2aa`、`wcag21a`、`wcag21aa`、`wcag22aa`、`best-practice` で選ぶ。
- 画面ごとに、ライトとダークの配色（`prefers-color-scheme`）で流す。
- 違反が 1 件でもあればテストを失敗にし、`incomplete` を含む結果の全体を HTML report に添付する。
- 対象はこの repo の画面に限り、Keycloak のログインの画面を含めない。
- 流れの中でしか届かない状態（競合の選択肢、決済の欄の状態）は、その流れを確かめる spec の中で同じ補助を呼ぶ。

これは ADR-027 の Consequences の Neutral にある「axe は利用する要件が生じるまで追加しない」を改める。
ADR-027 の jest-dom、Playwright、fast-check についての記述は変えない。

## Consequences

### Positive

- 描画の色に依存する `color-contrast` と、ページ全体の landmark の規則を、merge の前に止められる。
- 配色を足す変更（[#343](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/343)）で、明暗の両方の contrast を機械で確かめられる。
- 違反の規則の id、対象の selector、`helpUrl` が失敗の文言に出るので、直す箇所を report から探せる。

### Negative

- `task e2e` の所要時間が、画面と配色の組の数だけ延びる。
  #335 の調査では、1 回の `analyze()` が 0.3 秒から 2.6 秒（中央値 0.65 秒）だった。
- 違反を見つけるのは、E2E を流した変更だけである。E2E の CI が動かない変更（docs だけなど）では流れない。
- axe-core の版を上げると規則が増え、変更していない画面で E2E が落ちることがある。

### Neutral

- axe が見つけられるのは WCAG の違反の一部である。keyboard の操作と読み上げは、手動でも確かめる。
- `incomplete`（axe が自動で決められなかった項目）は失敗にせず、report に残して人が読む。
- 部品の単位で axe を流す要件（Storybook の導入など）が生じたら、別の ADR で決める。

## Alternatives Considered

### jsdom の component test で axe を流す

- **Description**：`axe-core` か `vitest-axe` を component test に入れ、部品ごとに流す。
- **Pros**：E2E の環境が要らず、速い。部品の単位で違反の場所が分かる。
- **Cons**：jsdom は描画の色を計算しないので、見つかった違反のうち `color-contrast` の 2 件を `incomplete` にして見逃す。ページ全体の landmark の規則も部品の描画では判定できない。

### Storybook の a11y addon で story に流す

- **Description**：比較の相手と同じく、Storybook を入れ、story ごとに axe を流す。
- **Pros**：実ブラウザで部品の状態ごとに流せる。
- **Cons**：Storybook そのものを入れる判断が要り、#284 で入れないとしている。ページ全体の landmark は story では確かめられない。

### lint と手動の確認だけを続ける

- **Description**：ADR-027 のとおり axe を入れない。
- **Pros**：依存と E2E の時間が増えない。
- **Cons**：lint と jsdom のテストが通る画面に、実在の違反が 4 種残っていた。

## References

- [#647: axe で画面のアクセシビリティの違反を止め、見つかった違反を直す](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/647)
- [#335: アクセシビリティを検査で止める](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/335)
- [ADR-027: Frontend のテスト基盤を標準化する](ADR-027-adopt-frontend-testing-stack.md)
- [ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る](ADR-057-adopt-playwright-for-e2e-tests.md)
- [Playwright: Accessibility testing](https://playwright.dev/docs/accessibility-testing)
- [axe-core: Rule descriptions](https://github.com/dequelabs/axe-core/blob/develop/doc/rule-descriptions.md)
