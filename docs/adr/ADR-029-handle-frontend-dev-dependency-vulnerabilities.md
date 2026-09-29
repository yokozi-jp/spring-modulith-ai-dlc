# ADR-029: Frontend開発依存の既知脆弱性をoverrideと期限付きignoreで扱う

## Status

Proposed

## Date

2026-09-29

## Context

Snyk Open SourceのPull Request checkは、Frontendの `package.json` について新たに持ち込まれた脆弱性があると失敗する。
この設定は重大度と修正版の有無を問わず、devDependenciesも検査対象に含む。

shadcn/ui、Orval、`@shadcn/lint`、`eslint-plugin-better-tailwindcss`、React Doctorの導入により、次の推移的依存が検出された。

- **undici 7.24.4、7.29.0**：Orvalが使う `@scalar/json-magic` が完全一致のversionで固定している。修正版は7.29.1である。
- **braces 3.0.3、deepmerge 4.3.1**：shadcn CLIの依存であり、修正版が公開されていない。
- **uri-js 4.4.1**：ESLint 10が依存する `ajv@6` を経由して入る。修正版が公開されておらず、最新のESLintも `ajv@^6` を要求する。

どれも開発時とビルド時に動くツールの依存であり、ブラウザ向けの実行時バンドルには含まれない。
ただし `shadcn` packageは `shadcn/tailwind.css` を提供するため、devDependenciesから外せない。

## Decision

修正版がある脆弱性は、pnpmのoverrideで修正版へ引き上げる。
`frontend/pnpm-workspace.yaml` に `"undici@<7.29.1": 7.29.1` を追加し、8系のundiciには影響させない。

修正版がない脆弱性は、`frontend/.snyk` で依存経路を限定したignoreにする。
ignoreには理由と90日の期限を付け、期限切れで再びcheckが失敗するようにする。
経路を指定するため、同じpackageが別の経路（たとえば実行時依存）から入った場合は検出される。

## Consequences

### Positive

- Snyk Open SourceのPull Request checkを、検査範囲を狭めずに通せる。
- 受け入れたリスクが、脆弱性ID、依存経路、理由、期限の組で記録される。

### Negative

- braces、deepmerge、uri-jsの脆弱なコードは開発環境に残る。
  第三者のshadcnレジストリ定義をマージすると、deepmergeのprototype pollutionを突かれる可能性がある。
- `@scalar/json-magic` が宣言したundiciのversionを上書きするため、互換性はFrontendの検証（`task fe-verify`）でしか確かめていない。
- overrideとignoreは上流が修正しても自動では消えないため、期限到来時や依存更新時に見直す必要がある。

### Neutral

- Snyk SCM連携は、manifestと同じdirectoryの `.snyk` を読む前提で `frontend/.snyk` に置く。
  リポジトリ直下の `.snyk` はSnyk CodeとSecretsの除外設定として維持する。

## Alternatives Considered

### Snykの組織設定を緩める

- **Description**：高重大度のみ、または修正版がある脆弱性のみでcheckを失敗させる。
- **Pros**：ignoreを書かずに済む。
- **Cons**：Backendを含む全projectで検出範囲が狭まり、どのリスクを受け入れたかが記録に残らない。

### devDependenciesを検査対象から外す

- **Description**：Snykの組織設定でdevDependenciesの検査を無効にする。
- **Pros**：開発ツール由来の検出がすべて消える。
- **Cons**：開発者の端末やCIで動くツールのサプライチェーンリスクが見えなくなる。

### 該当ツールを導入しない

- **Description**：shadcn、Orval、ESLint系pluginを外す。
- **Pros**：脆弱な推移的依存が消える。
- **Cons**：ADR-024、ADR-025で採用したUI基盤、API client生成、lint規則を失う。

## References

- [Snyk: Ignore vulnerabilities using the Snyk CLI](https://github.com/snyk/user-docs/blob/main/developer-tools/snyk-cli/scan-and-maintain-projects-using-the-cli/ignore-vulnerabilities-using-the-snyk-cli.md)
- [pnpm Settings: overrides](https://pnpm.io/settings#overrides)
- [`frontend/.snyk`](../../frontend/.snyk)
- [`frontend/pnpm-workspace.yaml`](../../frontend/pnpm-workspace.yaml)
