---
type: Convention
title: コミットメッセージの規約
description: コミットメッセージを Conventional Commits と commitlint に合わせて書くための規約。subject の大文字小文字、末尾のピリオド、type の選び方を定める。コミットを作る前に読む。
tags: [convention, repository, git, conventional-commits]
---

# コミットメッセージの規約

コミットメッセージは Conventional Commits 形式で書き、commitlint の検査を通す。
subject は小文字始まりにし、末尾にピリオドを置かない。

検査の正本は [commitlint.config.mjs](../../commitlint.config.mjs) であり、`@commitlint/config-conventional` を適用する。
この検査は commit-msg フックと CI の両方で走る。

## 形式

```text
<type>(<scope>): <subject>

<body>

<footer>
```

- `type` は小文字で、`feat`、`fix`、`docs`、`style`、`refactor`、`perf`、`test`、`build`、`ci`、`chore`、`revert` から選ぶ。
- `scope` は任意で、変更した領域を表す。
- `subject` は変更内容の要約を書く。

## 踏みやすい規則

エージェントが commitlint で弾かれやすい規則を次に挙げる。

- subject を小文字始まりにする。固有名詞も文頭では小文字で書き始める（`fix(ci): collectorの検査を…`）。sentence-case、start-case、pascal-case、upper-case は `subject-case` で弾かれる。
- subject の末尾にピリオドを置かない（`subject-full-stop`）。
- `type` と `subject` を空にしない（`type-empty`、`subject-empty`）。
- header は 100 文字以内にする（`header-max-length`）。
- body と footer も1行100文字以内にし、長い文は途中で改行する（`body-max-line-length`、`footer-max-line-length`）。
- body と footer の前に空行を置く（`body-leading-blank`、`footer-leading-blank`）。

## 破壊的変更

破壊的変更は `type` の後に `!` を付けるか、footer に `BREAKING CHANGE:` を書く。
リリースの版の決まり方は [リリース管理](release-management.md) に従う。
