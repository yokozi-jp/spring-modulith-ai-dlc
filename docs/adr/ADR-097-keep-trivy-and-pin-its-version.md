---
type: ADR
title: 'ADR-097: Trivy を使い続け、本体の版を trivy.yml で明示して固定する'
description: 2026 年 3 月の Trivy 配布経路の侵害を受け、Trivy を続けることと、action が取る本体の版を trivy.yml に明示することを決める。
tags: [adr, security, ci, supply-chain]
---

# ADR-097: Trivy を使い続け、本体の版を trivy.yml で明示して固定する

## Status

Proposed

## Date

2026-11-19

## Context

2026 年 3 月 19 日に Trivy の配布経路が侵害された（[GHSA-69fq-xp46-6x23](https://github.com/advisories/GHSA-69fq-xp46-6x23)、CVE-2026-33634）。
悪意のある Trivy v0.69.4 が公開され、`aquasecurity/trivy-action` の version tag の大半と `aquasecurity/setup-trivy` の tag が差し替えられた。
別のリポジトリ（agent-software-factory）はこの侵害を理由に Trivy をやめている。
このリポジトリは Trivy を `trivy.yml` で使う。使い続けるかと、action が内部で取る本体の版を固定できているかを確かめる必要があった（#355）。

確かめた事実は次のとおり。

- `trivy.yml` は `trivy-action` を commit の SHA（v0.36.0）で固定している。tag の差し替えの影響は直接は受けない。
- この SHA の `trivy-action` は `setup-trivy` を SHA（v0.2.6）で固定して呼び、`setup-trivy` はインストールスクリプトを commit の SHA で固定している。
- `trivy-action` の `version` 入力の既定値は `v0.70.0` で、`trivy.yml` は指定していない。本体は既定値に依存して固定されていた。
- 本体は Release のタグ（v0.70.0）で取得する。バイナリのハッシュは固定していない。
- 脆弱性 DB は `mirror.gcr.io` のメジャー版タグ（`trivy-db:2`、`trivy-java-db:1`）で取得する。
- このリポジトリの最初の commit は 2026-08-07 で、侵害の期間（3 月 19 日から 22 日）より後なので、侵害された版を動かしたことはない。
- GitHub は、サードパーティ action の固定を、lockfile、full-length commit SHA、信頼できる作者のタグの順に勧める（[Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)）。

## Decision

Trivy を使い続ける。置き換えもやめもしない。

- `trivy.yml` の 2 つの `trivy-action` のステップに `version: v0.70.0` を指定し、本体の版を明示する。
- `version` は Dependabot が追わない。`trivy-action` を上げるときに、既定値の変更と本体の版を手で見直す。
- dep-scan を並べて動かす（[ADR-095](ADR-095-scan-dependencies-with-dep-scan-alongside-trivy.md)）ので、片方が侵害されても検査は残る。
- workflow dependency lockfile は今回採用しない（Alternatives Considered）。

## Consequences

### Positive

- 本体の版が `trivy.yml` に出るので、action の更新で既定値が変わっても、差分に出る。
- 続ける理由と、固定の範囲が文書に残る。

### Negative

- `version` を手で更新するため、Trivy 本体の更新が遅れうる。
- 本体のバイナリのハッシュは固定していない。Release のタグが差し替えられる攻撃は防げない。

### Neutral

- lockfile の採用は、提供段階の確認とローカル action の書き換えの影響確認を終えてから、別に判断する。
- 脆弱性 DB のタグは固定しない。DB はデータで、実行物ではない。

## Alternatives Considered

### 選択肢1: Trivy をやめる、または Grype と Syft に替える

- **Description**：侵害を理由に別の道具へ移る。
- **Pros**：配布経路の侵害の履歴がある道具を避けられる。
- **Cons**：SHA 固定のおかげで実害がなく、移行の費用に見合う根拠がない。dep-scan も並べたので、検査は一つに偏らない。

### 選択肢2: workflow dependency lockfile を採用する

- **Description**：`gh actions-lock` で、推移的な依存も含めて action を固定し、リポジトリの ID を実行時に照合する。
- **Pros**：GitHub が最善策とする。Dependabot がロックの更新も行う。
- **Cons**：Trivy 本体のような action が取りに行く物は対象外である。ローカル action（`setup-mise`）の参照の書き換えが要る。機能の提供段階は未確認。別の判断にする。

## References

- Issue #355
- [GHSA-69fq-xp46-6x23](https://github.com/advisories/GHSA-69fq-xp46-6x23)
- [Workflow dependency lockfiles](https://docs.github.com/en/actions/concepts/security/workflow-dependency-lockfiles)
- [ADR-095](ADR-095-scan-dependencies-with-dep-scan-alongside-trivy.md)
