---
type: ADR
title: 'ADR-091: エージェントの commit を本物の index の写しから作る'
description: フックの間に同じ worktree のほかの作業が git add しても、commit に混ぜず、その git add も止めないように、agent-commit は本物の index の写しで確かめ、stage し、commit する。
tags: [adr, tooling, git, agents]
---

# ADR-091: エージェントの commit を本物の index の写しから作る

## Status

Proposed

## Date

2026-11-18

## Context

エージェントは `task agent-commit` で commit する（[開発ワークフロー](../tooling/dev-workflow.md) の「エージェントがcommitとpushをするとき」）。
pre-commit のフックは tool call の上限の 120 秒より長くかかることがある。
そこで commit はバックグラウンドで動き、エージェントは `task agent-wait` で終わりを待つ。

待つ間に、同じ worktree でほかの作業（別の Task、別のエージェント、利用者）が `git add` することがある。
この要件が設計を決める。
満たしたい性質は 2 つある。

- フックの間にほかの作業が `git add` したものを、commit に混ぜない。
- ほかの作業の `git add` を失敗させない。

git の動きのうち、設計を左右するものが 2 つある。

- `git commit` は pre-commit の間 index をロックせず、フックの後に index を読み直す。本物の index から commit すると、フックの間の `git add` が混ざる。
- `git commit --only <パス>`（`git commit -- <パス>`）は、フックの間ずっと本物の index の `index.lock` を持つ。その間、ほかの作業の `git add` は失敗する。

## Decision

`agent-commit` は、始めるときに本物の index を写し、確かめるのも、stage するのも、commit するのも写しにする。
`git commit` には `GIT_INDEX_FILE` で写しを渡す。

commit ができたら、本物の index のうち、commit が変えたパスだけを commit に合わせる。
写してからほかの作業が stage し直したパスは、その stage を残して知らせる。
合わせるときは `index.lock` を持ってから本物の index を読み、合わせた内容を名前の変更で置く。

## Consequences

### Positive

- フックの間のほかの作業の `git add` は、commit に入らず、失敗もしない。
- フックが直して stage したものは写しに入るので、commit に入る。

### Negative

- commit の後に本物の index を合わせる手順が要る。
- 合わせる前にほかの作業が古い index から commit すると、commit のパスが戻ることがある。これを見分けて知らせる処理も要る。
- `index.lock` を取れないか置き換えに失敗すると、本物の index は commit を打ち消す内容を stage したまま残る。`agent-wait` は合わせるコマンドを出し、実行するまで次の `agent-commit` を止める。
- 写しは index の更新時刻を保たなければならない（`cp -p`）。保たないと git は stat を信じ、index と同じ秒に同じ大きさで書き換えたファイルの変更を見落とす。

### Neutral

- 確かめてから写すと、その間の `git add` が混ざる。そこで写しは確かめる前に作る。
- 同じ worktree の 2 つの `agent-commit` は同じ HEAD から始まり、後に終わったほうを git が拒む。そこで前の commit が動いている間は、次の `agent-commit` を止める。

## Alternatives Considered

### 選択肢1: 本物の index から git commit する

- **Description**：本物の index に stage してから、そのまま `git commit` を動かす。
- **Pros**：commit の後に本物の index を合わせる手順が要らない。
- **Cons**：フックの間にほかの作業が `git add` したものが commit に混ざる。

### 選択肢2: git commit --only で渡したパスだけを commit する

- **Description**：`git commit -- <パス>` で、渡したパスだけを commit する。
- **Pros**：commit の中身は渡したパスに限られ、git が本物の index も合わせる。
- **Cons**：フックの間ずっと `index.lock` を持つので、ほかの作業の `git add` が失敗する。フックは数分かかることがある。

### 選択肢3: commit の間はほかの作業に git add をさせない

- **Description**：ロックか運用の決まりで、commit の間のほかの作業の `git add` を止める。
- **Pros**：本物の index から commit しても混ざらない。
- **Cons**：ほかの作業は利用者や別の Task で、`agent-commit` の外の `git add` を止める手段がない。

## References

- [開発ワークフロー](../tooling/dev-workflow.md)
- `tooling/agent-git/agent-git.sh`
- Pull Request #393（`task agent-commit`、`task agent-push`、`task agent-wait` を足す）
