---
type: Architecture Decision Record
title: 'ADR-035: jscpd と Knip を品質ゲートに採用する'
description: 重複コードと未使用コードを Pull Request ごとに検出するため、jscpd と Knip を品質ゲートに採用する決定。
tags: [adr, quality, ci, lint]
---

# ADR-035: jscpd と Knip を品質ゲートに採用する

## Status

Proposed

## Date

2026-10-01

## Context

Frontend は Oxlint と TypeScript、Backend は PMD と SpotBugs と Error Prone で静的解析している。
これらの検査は、Frontend と Backend にまたがる重複コードを検出しない。
TypeScript の未使用検査も、未参照ファイル、未使用 export、未使用依存パッケージまでは検出しない。

重複コードと Frontend の未使用コードを Pull Request ごとに検出し、新しい問題の混入を防ぐ必要がある。
一方で、TanStack Router と jOOQ の生成コードを検査すると、開発者が修正できない重複や参照関係を診断する可能性がある。
ローカルと CI で異なるコマンドを持つと検査結果がずれるため、既存方針どおり Task を共通入口にする。

## Decision

ルートの npm 開発依存として jscpd を固定バージョンで導入する。
Frontend と Backend の手書きソースを一回の jscpd 実行で検査する。
導入時に検出した重複率は 3.19% であるため、品質ゲートの閾値を 3.2% に固定し、既存の clone を解消する変更で段階的に下げる。
TanStack Router の生成ファイルと jOOQ の生成ディレクトリは検査対象から外す。

Frontend の pnpm 開発依存として Knip を固定バージョンで導入する。
Knip は Frontend の未参照ファイル、未使用 export、未使用依存パッケージを検査し、既存の `fe-verify` に含める。
誤検出や将来利用する依存は一括で無視せず、設定上の entry または対象を個別に指定し、理由をコードまたは判断記録から追跡できる状態にする。

ローカルと CI は Taskfile の同じ公開タスクを呼ぶ。
jscpd は既存のリポジトリ横断静的解析 workflow で実行し、Knip は既存の Frontend CI が呼ぶ `fe-verify` から実行する。

## Consequences

### Positive

- Frontend と Backend の重複コードを同じ基準で検出できる。
- TypeScript のコンパイラ検査では見つからない未参照ファイル、未使用 export、未使用依存パッケージを検出できる。
- ローカルと CI が Taskfile の同じコマンドを使うため、検査結果の差を抑えられる。
- 生成コードを除外し、開発者が修正できるコードだけを品質ゲートの対象にできる。

### Negative

- npm と pnpm の二つの lockfileで検査ツールを管理する必要がある。
- jscpd の閾値は導入時の重複率 3.19% を通す 3.2% から始めるため、小さな重複の追加を直ちに検出できない場合がある。
- 既存の clone を解消した変更で閾値も下げなければ、品質基準が改善されない。
- Knip が framework や外部コマンドから使われる entry を認識できない場合、設定の保守が必要になる。

### Neutral

- jscpd は Java と TypeScript の構文を同一言語として比較せず、それぞれの重複を一回の実行で報告する。
- 既存の Oxlint、PMD、SpotBugs、Error Prone、Semgrep は役割が異なるため維持する。
- 検査ツールの更新は固定バージョンを明示的に変更する Pull Request で行う。

## Alternatives Considered

### Alternative 1: Frontend と Backend で jscpd を別々に導入する

- Description：Frontend の pnpm と Backend の Gradle から別々に重複検査を実行する。
- Pros：各モジュールの検査を独立して実行できる。
- Cons：同じツールと基準を二重管理し、リポジトリ全体の結果を一度に確認できない。

### Alternative 2: 既存の静的解析だけを使う

- Description：Oxlint、TypeScript、PMD、SpotBugs、Error Prone、Semgrep のみを維持する。
- Pros：依存と CI 時間が増えない。
- Cons：トークン列ベースの重複と、Frontend の未参照ファイルや未使用 export や未使用依存パッケージが検査されない。

### Alternative 3: jscpd と Knip を CI から直接実行する

- Description：GitHub Actions にツールのコマンドを記述する。
- Pros：CI の定義だけで検査を開始できる。
- Cons：ローカルと CI の実行方法が分かれ、引数や除外設定がずれる。

## References

- [jscpd](https://github.com/kucherenko/jscpd)
- [Knip](https://knip.dev/)
- [ADR-010: プロジェクトのタスクランナーにTaskを採用する](ADR-010-adopt-taskfile.md)
- [ADR-031: Frontend の型検査を厳格化し、tsconfig を正本にする](ADR-031-tighten-frontend-typescript-checks.md)
- `Taskfile.yml`
- `.github/workflows/static-analysis.yml`
