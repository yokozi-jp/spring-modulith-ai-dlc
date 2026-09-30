---
type: Architecture Decision Record
title: 'ADR-022: semver 6.3.1 を pnpm trust policy の例外にする'
description: Babel 経由の避けられない依存を通すため、semver 6.3.1 を pnpm trust policy の例外にする決定。
tags: [adr, frontend, supply-chain, pnpm]
---

# ADR-022: semver 6.3.1 を pnpm trust policy の例外にする

## Status

Proposed

## Date

2026-09-29

## Context

Frontend は TanStack Router の Vite plugin を使い、ファイル構成から型付きルートツリーを生成する。
`@tanstack/router-plugin@1.168.40` は `@babel/core@7.29.7` を介して `semver@6.3.1` を必要とする。
この `semver` は Babel がバージョン制約を解析し、Babel本体とpluginの互換性を検証するためのビルド時依存であり、ブラウザ向けの実行時バンドルには含まれない。

Frontend の pnpm workspace は `trustPolicy: no-downgrade` を設定している。
このポリシーは、公開日時が前のリリースに存在した信頼証拠より弱いリリースを拒否する。
`semver@6.3.1` には npm provenance がないため、pnpm 11.21.0 は `ERR_PNPM_TRUST_DOWNGRADE` で導入を止める。

`semver@6.3.1` は2023年7月10日に公開され、npmの `node-semver` リポジトリを公開元としており、registry が配布するtarballのintegrityとshasumは確認できる。
しかし、integrityは取得内容の一致を検証するだけで、公開主体をprovenanceによって証明しない。
TanStack Router plugin の対応バージョンを下げても Babel が同じsemver範囲を要求するため、この依存経路は残る。

## Decision

`frontend/pnpm-workspace.yaml` の `trustPolicyExclude` に `semver@6.3.1` を追加する。
例外はpackage名とversionの完全一致に限定し、`trustPolicy: no-downgrade`、7日間の `minimumReleaseAge`、`blockExoticSubdeps` は維持する。

`semver` のバージョンを範囲指定で例外化せず、別バージョンへ自動的に例外が広がらないようにする。
BabelまたはTanStack Router pluginがこの依存を解消した時点で、lockfileに `semver@6.3.1` が残っていないことを確認して例外を削除する。

## Consequences

### Positive

- TanStack Router の Vite pluginを導入し、開発時とビルド時に型付きルートツリーを自動生成できる。
- 例外が `semver@6.3.1` だけに限定されるため、ほかの依存にはtrust levelの低下を拒否する方針が引き続き適用される。
- `minimumReleaseAge` とregistry配布物のintegrity検証は維持される。

### Negative

- `semver@6.3.1` について、npm provenanceによる公開主体の証明を要求しない。
- integrityが一致しても、正当な公開者が発行したことまではこの設定で保証できない。
- 依存経路が消えても例外は自動削除されないため、依存更新時に手動で見直す必要がある。

### Neutral

- `semver@6.3.1` はBabelのビルド時依存であり、Frontendの実行時コードから直接呼び出さない。
- ADRの状態はProposedとし、例外設定とRouter導入を同じPull Requestでレビューする。

## Alternatives Considered

### trust policyを無効化する

- **Description**：`trustPolicy` を `off` にする。
- **Pros**：信頼証拠のない依存を個別に管理せず導入できる。
- **Cons**：今回と無関係な全依存のtrust downgradeも検出できなくなるため、例外の範囲が広すぎる。

### 古い公開物を一律に検査対象外にする

- **Description**：`trustPolicyIgnoreAfter` を設定し、一定期間より古い全packageを検査対象外にする。
- **Pros**：古いpackageごとの例外列挙を減らせる。
- **Cons**：`semver@6.3.1` 以外の古い依存にも例外が広がり、どのpackageのリスクを受け入れたかが不明確になる。

### code-based routingを使う

- **Description**：TanStack Router pluginを導入せず、route treeを手書きする。
- **Pros**：Babelと `semver@6.3.1` の依存経路を追加せずに済む。
- **Cons**：要求しているファイルベースルーティングとルートツリー自動生成を利用できない。

### semverの別majorへoverrideする

- **Description**：Babelが要求する `semver@^6.3.1` をsemver 7へ強制置換する。
- **Pros**：provenanceを持つ別バージョンを選べる可能性がある。
- **Cons**：Babelが宣言したmajor version制約を破り、未検証の互換性問題を持ち込む。

## References

- [pnpm Settings: trustPolicyExclude](https://pnpm.io/settings/dependency-resolution#trustpolicyexclude)
- [TanStack Router: Installation with Vite](https://tanstack.com/router/latest/docs/framework/react/installation/with-vite)
- [npm: semver 6.3.1](https://www.npmjs.com/package/semver/v/6.3.1)
- [frontend/pnpm-workspace.yaml](../../frontend/pnpm-workspace.yaml)
