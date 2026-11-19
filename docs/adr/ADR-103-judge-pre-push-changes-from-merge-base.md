---
type: ADR
title: 'ADR-103: pre-push は origin/main との merge-base からの差分で検査を選び、成功した job の出力も残す'
description: pre-push の検査を、直前の push ではなく origin/main との merge-base からの差分で選ぶことと、Lefthook が成功した job の出力も表示することを決める。
tags: [adr, tooling, lefthook]
---

# ADR-103: pre-push は origin/main との merge-base からの差分で検査を選び、成功した job の出力も残す

## Status

Proposed

## Date

2026-11-19

## Context

pre-push は、変更した領域だけを検査するため、Lefthook の glob で job を選ぶ。
この判定の基準と出力の設定は、これまで `docs/tooling/lint-and-test.md`（Reference）に理由ごと書かれていた。
Issue #532 で、Reference から設計の理由を分けるため、この ADR に移す。

## Decision

pre-push の「変更」は、push 済みかどうかや rebase の有無によらず、`origin/main` との merge-base からの差分で判定する。
`origin/main` がなく merge-base を取れないときは、全ファイルを対象にして全検査を走らせる。

Lefthook の `output` は、版の表示、skip の行、`EXECUTE` の行を省き、job の出力と要約を出す。
成功した job の出力も残す。`adr-check` の注意喚起が成功の扱いで出るためである。

## Consequences

### Positive

- 分岐後に main へ入った変更では、触っていない領域の検査は走らない。
- rebase の前後で判定が変わらない。
- `adr-check` の注意喚起が、成功した pre-push でも見える。

### Negative

- 2 回目以降の push でも、ブランチ全体の変更に対して検査が走る。
  一度 backend を変えたブランチでは、以降 docs だけを直して push しても backend の検査が毎回走る。

### Neutral

- merge-base を取れない環境（`origin/main` がない clone）では、全検査が走る。

## Alternatives Considered

### Alternative 1: 直前の push からの差分で判定する

- Description：前回 push した commit からの差分だけを見る。
- Pros：同じ領域の検査を繰り返さない。
- Cons：rebase の後は main の変更を含んでしまい、触っていない領域の検査が走る。
  上の重複を受け入れる方が判定が単純である。

## References

- [Lintとテストのリファレンス](../tooling/lint-and-test.md#gitフック対応)
- `lefthook.yml`
