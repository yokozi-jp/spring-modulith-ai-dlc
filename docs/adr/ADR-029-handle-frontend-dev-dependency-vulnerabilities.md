---
type: ADR
title: 'ADR-029: Frontend開発依存の既知脆弱性をoverrideで扱う'
description: 修正版のある推移的脆弱性を pnpm の override で引き上げ、Snyk による検査はやめる決定。
tags: [adr, frontend, security, dependencies, snyk]
---

# ADR-029: Frontend開発依存の既知脆弱性をoverrideで扱う

## Status

Proposed

## Date

2026-09-29

## Context

shadcn/ui、Orval、`@shadcn/lint`、`eslint-plugin-better-tailwindcss`、React Doctorの導入により、次の推移的依存が検出されていた。

- **undici 7.24.4、7.29.0**：Orvalが使う `@scalar/json-magic` が完全一致のversionで固定している。
  修正版は7.29.1である。
- **braces 3.0.3、deepmerge 4.3.1**：shadcn CLIの依存であり、修正版が公開されていない。
- **uri-js 4.4.1**：ESLint 10が依存する `ajv@6` を経由して入る。
  修正版が公開されておらず、最新のESLintも `ajv@^6` を要求する。

どれも開発時とビルド時に動くツールの依存であり、ブラウザ向けの実行時バンドルには含まれない。

初版は、Snyk Open SourceのPull Request checkを通すために、修正版のない脆弱性を `frontend/.snyk` の期限付きignoreで扱う決定だった。
Snykは無料枠の制限にすぐ達し、検査が止まるため、Snykでの検査をやめることにした。
ignoreは不要になったので、この決定から外した。

## Decision

修正版がある脆弱性は、pnpmのoverrideで修正版へ引き上げる。
`frontend/pnpm-workspace.yaml` に `"undici@<7.29.1": 7.29.1` を追加し、8系のundiciには影響させない。

Snykでの検査はやめる。
`.snyk`（Snyk CodeとSecretsの除外設定）と `frontend/.snyk` を削除する。
SnykのOrgのprojectとGitHub Appも外す。
Snyk Code、Secrets、Open Sourceの代わりは、CodeQL、Semgrep、betterleaks、trivyが担う。
修正版のないbraces、deepmerge、uri-jsは、開発環境にそのまま残す。

## Consequences

### Positive

- 無料枠の制限でPull Requestのcheckが止まらなくなる。
- ignoreの期限（2026-12-28）を追う運用が要らなくなる。

### Negative

- braces、deepmerge、uri-jsの脆弱なコードは開発環境に残る。
  第三者のshadcnレジストリ定義をマージすると、deepmergeのprototype pollutionを突かれる可能性がある。
- braces、deepmerge、uri-jsの検出は、どこにも残らない。
  trivyは開発依存を既定で除き（`--include-dev-deps` なし）、修正版のない脆弱性も `ignore-unfixed: true` で出さず、`exit-code: "0"` でPull Requestを止めない。
  Dependabot alertsはGitHub Advisory Databaseを使い、この3件に該当するadvisoryがない。
- `@scalar/json-magic` が宣言したundiciのversionを上書きするため、互換性はFrontendの検証（`task fe-verify`）でしか確かめていない。
- overrideは上流が修正しても自動では消えないため、依存更新時に見直す必要がある。

### Neutral

- SnykのGitHub Appを外すまで、Pull RequestにSnykの検査が走る。
  `.snyk` を消すため、削除前に除外していたものと、ignoreしていた3件が再び報告される。

## Alternatives Considered

### Snykを続けて期限付きignoreを書く

- **Description**：`frontend/.snyk` に依存経路を限定したignoreを置き、90日ごとに更新する。
- **Pros**：受け入れたリスクが、脆弱性ID、依存経路、理由、期限の組で記録される。
- **Cons**：無料枠の制限にすぐ達し、ignoreの更新も手作業になる。

### 該当ツールを導入しない

- **Description**：shadcn、Orval、ESLint系pluginを外す。
- **Pros**：脆弱な推移的依存が消える。
- **Cons**：ADR-024、ADR-025で採用したUI基盤、API client生成、lint規則を失う。

## References

- [pnpm Settings: overrides](https://pnpm.io/settings#overrides)
- [frontend/pnpm-workspace.yaml](../../frontend/pnpm-workspace.yaml)
