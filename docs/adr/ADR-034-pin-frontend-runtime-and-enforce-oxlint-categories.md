# ADR-034: Frontend ランタイムを固定し Oxlint 全カテゴリを強制する

## Status

Proposed

## Date

2026-09-30

## Context

Frontend は Vite+ をツールチェーンの単一入口とし、Node.js は Vite+ が管理する。
これまで Node.js の版はプロジェクトに明示されておらず、Vite+ はローカルで LTS を選び、Frontend CI は `actions/setup-node` にメジャー版 `22` だけを渡していた。
ローカルと CI で解決される Node.js の版が食い違い、片方でだけ再現する不具合を招きうる。
Vite+ とその Vite 互換コアも、`pnpm-workspace.yaml` の catalog でキャレット範囲（`^0.3.3`）を指定していた。

Lint は `vp check` へ集約し、型認識 Lint と型検査、React、Tailwind、shadcn、任意 HTML sink の禁止まで有効化していた。
一方で Oxlint のカテゴリは明示しておらず、既定では correctness 系の多くが警告のままで、警告だけでは `vp check` が失敗しなかった。
検出力を上げるため、Oxlint の安定カテゴリをすべてエラーへ引き上げたい。
ただし、全カテゴリを有効にすると、named export 禁止と default export 禁止のように同時に満たせない規則や、automatic JSX runtime で不要になる規則、Vite や TanStack Router や Vitest の標準構文を禁じる規則が含まれる。

## Decision

Node.js の版を `frontend/.node-version`（`24.21.0`）で固定し、これを正本とする。
Vite+ は `.node-version` を最優先で解決し、Frontend CI も `node-version-file: frontend/.node-version` で同じ値を参照する。
Vite+ と Vite 互換コアは catalog で完全固定（`0.3.3`）し、更新は明示的な依存更新の PR で行う。

Oxlint の `correctness`、`suspicious`、`pedantic`、`perf`、`style`、`restriction` をすべてエラーにする。
開発中の `nursery` は有効化しない。
組み込みプラグインは eslint core に加えて `unicorn`、`typescript`、`oxc`、`react`、`import`、`vitest`、`jsx-a11y`、`promise` を有効にする。
`denyWarnings` で警告も CI を止め、`reportUnusedDisableDirectives` で不要になった抑制コメントをエラーとして検出する。
`typeAware` と `typeCheck` は維持する。

カテゴリ全体を維持したうえで、次のものに限って個別規則を無効化または調整する。
相互に矛盾する規則（named/default export の強制など）、automatic JSX runtime で不要な規則、Vite の default export や TanStack Router の named export や CSS の副作用 import、React の `className` と props spread、modern な async / optional chaining / rest、Vitest の hook や import に関する標準構文、Oxfmt や型推論と役割が重複する整形系規則である。
ファイル単位で Lint を無効化したり、カテゴリ全体を警告へ戻したりしない。
範囲を限定する調整は、Node 側 Lint ツール（`lint/**`）、shadcn 由来コンポーネント（`src/components/ui/**`）、ルートモジュール（`src/routes/**`）、テストファイルの override で行う。

## Consequences

### Positive

- ローカルと CI が同じ Node.js 版を使い、版差に由来する不具合を減らせる。
- Vite+ とコアの版が固定され、ツールチェーンの更新を明示的な変更として扱える。
- Oxlint の安定カテゴリ全体が品質ゲートになり、警告と不要な抑制コメントも CI を止める。
- 例外を相互矛盾と framework 必須構文に限定し、意味のある規則は有効なまま残せる。

### Negative

- 新しい依存や書き方が、これまで警告だった規則でエラーになり、修正が必要になる場合がある。
- Oxlint の更新で規則やカテゴリの内容が変わると、例外リストの見直しが必要になる。
- Node.js の版更新は `.node-version`、CI、Vite+ の三者を揃える手順を伴う。

### Neutral

- eslint core は Vite+ が常に有効化するため、`plugins` の正規化表示には現れない。
- 型なしの ESLint 互換 JS プラグイン API はアルファであり、`lint/**` の型安全規則は当該ディレクトリに限って無効化している。
- Lint 設定と例外の一覧は `docs/lint-and-test.md` に記述し、実装と一致させる。

## Alternatives Considered

### Alternative 1: `devEngines.runtime` で Node.js を固定する

- Description：`package.json` の `devEngines.runtime` に Node.js 版を書く。
- Pros：`package.json` に集約できる。
- Cons：pnpm と Vite+ の双方が Node.js を管理しようとし、ランタイム依存を二重に解決する。`.node-version` は Vite+ が最優先で読み、単一の正本になる。

### Alternative 2: カテゴリを段階的にエラー化する

- Description：correctness だけをエラーにし、他は警告のまま残す。
- Pros：初期の修正量が少ない。
- Cons：警告では `vp check` が失敗せず、品質ゲートにならない。コードベースが小さい今のうちに全カテゴリを強制する方が移行コストが小さい。

### Alternative 3: 規則を個別に列挙して有効化する

- Description：カテゴリを使わず、必要な規則だけを一つずつ有効にする。
- Pros：有効な規則を完全に制御できる。
- Cons：Oxlint の規則追加に追従できず、一覧の保守が重い。カテゴリでまとめて有効にし、例外だけを列挙する方が保守しやすい。

## References

- [ADR-031: Frontend の型検査を厳格化し、tsconfig を正本にする](./ADR-031-tighten-frontend-typescript-checks.md)
- [Oxlint: Built-in plugins](https://oxc.rs/docs/guide/usage/linter/plugins.html)
- [Oxlint: CLI reference（カテゴリ）](https://oxc.rs/docs/guide/usage/linter/cli.html)
- [Oxlint: Config file reference（`options`）](https://oxc.rs/docs/guide/usage/linter/config-file-reference.html)
- [Vite+: Environment（Node.js 選択）](https://viteplus.dev/guide/env)
- `frontend/vite.config.ts`
- `frontend/.node-version`
- `.github/workflows/frontend-ci.yml`
- `docs/lint-and-test.md`
