---
type: ADR
title: 'ADR-083: CodeQL を advanced setup で管理し、build-mode を明示する'
description: GitHub の Code scanning を default setup から、コミット済みワークフローの advanced setup へ移行し、Java を build-mode autobuild で解析する決定。
tags: [adr, security, ci, codeql, code-scanning]
---

# ADR-083: CodeQL を advanced setup で管理し、build-mode を明示する

## Status

Proposed

## Date

2026-10-22

## Context

セキュリティは、対象の問題を直接検出する検査で担保しており、CodeQL もその一つである（[ADR-017](ADR-017-adopt-trunk-based-repository-governance.md)）。
この CodeQL は、これまで GitHub の **Code scanning default setup**（リポジトリ設定で有効化する方式）で動いていた。
default setup にはコミット済みのワークフローがなく、設定はリポジトリ設定にだけ存在する。

default setup は Java を `build-mode: none`（ビルドレス抽出）で解析する。
このため Code Scanning の画面に「Java was extracted with build-mode set to 'none'」が常時表示される。
ビルドレス抽出は Gradle で実際にビルドせずソースだけを走査するため、依存解決やアノテーション処理に基づく一部のデータフローを取りこぼしうる。

default setup の方式は次の制約を生む。

- build-mode や対象言語、クエリスイートの構成がコードに残らず、設定変更の履歴とレビューがリポジトリの外にある。
- Java の解析精度を上げる（ビルドして解析する）には、方式を advanced setup に替えるしかない。

CodeQL をセキュリティ検査として位置づけた方針（ADR-017）を変える判断であり、CI の構成と実行コストに影響するため ADR に残す。

## Decision

CodeQL を default setup から、コミット済みワークフロー `.github/workflows/codeql.yml` による **advanced setup** へ移行する。

- 対象言語は `actions`、`java-kotlin`、`javascript-typescript` とする。
  default setup が別々に数えていた `javascript` と `typescript` は、advanced setup では `javascript-typescript` に集約する。
- Java（`java-kotlin`）は `build-mode: autobuild` で解析する。
  バックエンドは jOOQ の生成コードをコミット済み（[ADR-004](ADR-004-commit-jooq-generated-code.md)）で、データベースなしで `compileJava` が通るため、autobuild が成立する。
- `actions` と `javascript-typescript` は `build-mode: none` とする（ビルド不要の言語のため）。
- クエリスイート、スキャンのトリガ（push と pull_request、週次 schedule）は default setup の水準を保つ。
- 他のセキュリティワークフロー（Semgrep、Trivy、zizmor）と同じ規約に合わせる。
  アクションは SHA で固定しバージョンをコメントで添える、権限はジョブ単位で最小化する、`concurrency` で多重実行を打ち切る、checkout は `persist-credentials: false` にする。

advanced setup が SARIF をアップロードする前に、GitHub 側で default setup を無効化する。
default setup を残したまま advanced setup を動かすと、同じ言語の解析が二重になる。

## Consequences

### Positive

- Java の解析が autobuild で正確になり、「build-mode: none」の通知が解消する。
- build-mode、対象言語、クエリ構成、トリガがワークフローとしてコードに残り、変更が Pull Request でレビューできる。
- 他のセキュリティワークフローと pin・権限・並行制御の規約がそろう。

### Negative

- CodeQL 用に Gradle のビルドを回すため、Java の解析ジョブの CI 時間が default setup より増える。
- `.github/workflows/codeql.yml` の保守対象が一つ増える（codeql-action の版追従など）。

### Neutral

- default setup の無効化は GitHub の設定操作（Web またはAPI）で、コミットでは行えない。移行時に一度だけ必要になる。
- 実行コストが見合わなくなった場合は、default setup へ戻すか build-mode を none に下げる後続の判断があり得る。

## Alternatives Considered

### 選択肢1: default setup のまま build-mode: none を受容する

- **Description**：ワークフローを追加せず、「build-mode: none」を default setup の仕様として受け入れる。
- **Pros**：CI 構成が増えず、CI 時間も増えない。
- **Cons**：Java の解析精度が上がらず、build-mode や対象言語の構成がコードに残らない。通知も消えない。

### 選択肢2: advanced setup で Java も build-mode: none にする

- **Description**：ワークフローは追加するが、Java もビルドレスのまま解析する。
- **Pros**：構成をコードで管理でき、CI 時間の増加を避けられる。
- **Cons**：「build-mode: none」の通知が残り、解析精度の課題も解消しない。移行の主目的を満たさない。

## References

- [ADR-017: トランクベース開発とリポジトリ保護を採用する](ADR-017-adopt-trunk-based-repository-governance.md)
- [ADR-004: jOOQ 生成コードを Git 管理する](ADR-004-commit-jooq-generated-code.md)
- [CodeQL code scanning for compiled languages (build modes)](https://docs.github.com/en/code-security/code-scanning/creating-an-advanced-setup-for-code-scanning/codeql-code-scanning-for-compiled-languages)
