---
type: ADR
title: 'ADR-097: Trivy を使い続け、CI の Trivy 本体の版を明示して Taskfile にそろえる'
description: 2026 年 3 月の Trivy 配布経路の侵害を受け、Trivy を続けることと、CI の Trivy 本体の版を trivy.yml に明示して Taskfile の版にそろえることを決める。
tags: [adr, security, ci, supply-chain]
---

# ADR-097: Trivy を使い続け、CI の Trivy 本体の版を明示して Taskfile にそろえる

## Status

Proposed

## Date

2026-11-19

## Context

2026 年 3 月 19 日に Trivy の配布経路が侵害された（[GHSA-69fq-xp46-6x23](https://github.com/advisories/GHSA-69fq-xp46-6x23)、CVE-2026-33634）。
悪意のある Trivy v0.69.4 が公開され、`aquasecurity/trivy-action` の version tag の大半と `aquasecurity/setup-trivy` の tag が差し替えられた。
別のリポジトリ（yokozi-jp/agent-software-factory）はこの侵害を理由に Trivy をやめている。
このリポジトリは Trivy を `trivy.yml` で使う。使い続けるかと、action が内部で取る本体の版を固定できているかを確かめる必要があった（#355）。

確かめた事実は次のとおり。

- `trivy.yml` は `trivy-action` を commit の SHA（v0.36.0）で固定している。tag の差し替えの影響は直接は受けない。
- この SHA の `trivy-action` は `setup-trivy` を SHA（v0.2.6）で固定して呼び、`setup-trivy` はインストールスクリプトを commit の SHA で固定している。
- `trivy-action` の `version` 入力の既定値は `v0.70.0` で、`trivy.yml` は指定していなかった。本体は既定値に依存していた。
- Taskfile の `scan-vulns` は `aquasec/trivy:0.74.0` を digest で固定して動かす。CI（v0.70.0）とローカルで版が 4 つ離れていた。
- v0.70.0 には、細工した脆弱性 DB などの OCI artifact で任意の場所へファイルを書ける High の脆弱性がある（[GHSA-mcj4-mphf-j9ff](https://github.com/advisories/GHSA-mcj4-mphf-j9ff)、0.71.1 で修正）。`TRIVY_DB_REPOSITORY` を介して届くが、Aqua の既定の配布先は影響を受けない。
- 本体は Release の版（immutable な release）から取得し、インストールスクリプトが同じ release の `checksums.txt` で tarball の SHA-256 を検証する。Actions の cache に版ごとの binary があると、その検証は飛ぶ。
- `trivy.yml` のジョブは `contents: read` と `security-events: write` だけを持ち、checkout は `persist-credentials: false` である。侵害されても、盗めるものは `github.token` に限られる。
- このリポジトリの最初の commit は 2026-08-07 で、侵害の期間（3 月 19 日から 22 日）より後なので、侵害された版を動かしたことはない。
- GitHub は、サードパーティ action の固定を、lockfile、full-length commit SHA、信頼できる作者のタグの順に勧める（[Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)）。

## Decision

Trivy を使い続ける。置き換えもやめもしない。

- `trivy.yml` の `env` に `SCAN_TRIVY_VERSION: v0.74.0` を置き、2 つの `trivy-action` のステップの `version` に渡す。Taskfile の `scan-vulns` と同じ版にそろえる。名前は `TRIVY_` で始めない。`trivy-action` が `TRIVY_*` を CLI の環境変数に渡すためである。
- `trivy.yml` の版と Taskfile の版は、手で一緒に上げる。Dependabot は `version` の入力を追わない。
- workflow dependency lockfile は今回採用しない（Alternatives Considered）。

## Consequences

### Positive

- 本体の版が `trivy.yml` に明示され、`trivy-action` の更新で既定値が変わっても、本体は変わらない。
- v0.70.0 の High の脆弱性を避け、CI とローカルが同じ版になる。
- 続ける理由と、固定の範囲が文書に残る。
- 侵害されたときに盗めるものが小さいことを、判断の根拠として残す。

### Negative

- 版を手で上げるため、Trivy 本体の更新が遅れうる。`trivy-action` の更新を Dependabot が自動でマージしても、本体の版は上がらない。
- 本体のバイナリは、版（immutable な release）と `checksums.txt` で固定するが、digest では固定しない。cache から戻すと、その検証は飛ぶ。
- 版の置き場が、`trivy.yml` と Taskfile の 2 か所にある。

### Neutral

- 検査の対象の脆弱性 DB は、メジャー版のタグで取得する。細工した DB が書き込める問題は、本体の版で塞ぐ。
- lockfile の採用は、別に判断する（#500）。

## Alternatives Considered

### 選択肢1: Trivy をやめる、または Grype と Syft に替える

- **Description**：侵害を理由に別の道具へ移る。
- **Pros**：配布経路の侵害の履歴がある道具を避けられる。
- **Cons**：SHA 固定のおかげで実害がなく、移行の費用に見合う根拠がない。dep-scan を並べた（[ADR-095](ADR-095-scan-dependencies-with-dep-scan-alongside-trivy.md)）ので、検査は一つに偏らない。

### 選択肢2: workflow dependency lockfile を採用する

- **Description**：`gh actions-lock` で、推移的な依存も含めて action を固定し、リポジトリの ID を実行時に照合する。
- **Pros**：GitHub が最善策とする。Dependabot がロックの更新も行う。
- **Cons**：Trivy 本体のような action が取りに行く物は対象外である。ローカル action（`setup-mise`）の参照の書き換えが要る。機能の提供段階は未確認。別の判断にする。

### 選択肢3: Taskfile と同じ digest 固定のコンテナで CI の Trivy を動かす

- **Description**：`trivy-action` をやめ、`docker run aquasec/trivy:<版>@<digest>` を CI でも使う。版の置き場が一つになり、digest で固定できる。
- **Pros**：版の置き場が一つになり、digest で固定できる。
- **Cons**：SARIF の出力と Code Scanning への送信のステップを自分で書く。今回の範囲を超えるので、必要になれば別に判断する。

### 選択肢4: 既定値に頼り続ける

- **Description**：`version` を指定せず、SHA 固定だけで運用する。
- **Pros**：変更が要らない。
- **Cons**：既定値が v0.70.0 に決まっていることが、リポジトリから見えない。CI とローカルの版の差にも気づけなかった。

## References

- Issue #355
- [GHSA-69fq-xp46-6x23](https://github.com/advisories/GHSA-69fq-xp46-6x23)
- [GHSA-mcj4-mphf-j9ff](https://github.com/advisories/GHSA-mcj4-mphf-j9ff)
- [Secure use reference](https://docs.github.com/en/actions/reference/security/secure-use)
- [Workflow dependency lockfiles](https://docs.github.com/en/actions/concepts/security/workflow-dependency-lockfiles)
- [ADR-095](ADR-095-scan-dependencies-with-dep-scan-alongside-trivy.md)
