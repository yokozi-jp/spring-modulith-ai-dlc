---
type: ADR
title: 'ADR-031: Frontendの型検査を厳格化し、tsconfigを正本にする'
description: エディタと CI の型検査の食い違いを防ぐため、Frontend の型検査を厳格化し tsconfig を正本にする決定。
tags: [adr, frontend, typescript, quality]
---

# ADR-031: Frontendの型検査を厳格化し、tsconfigを正本にする

## Status

Proposed

## Date

2026-09-30

## Context

Frontendは TypeScript 7.0 を使い、`vp check`（tsgolint）と `vp run build` 内の `tsc` の二か所で型を検査する。
TypeScript 6.0 以降は `strict` と `noUncheckedSideEffectImports` が既定で有効になったため、従来の `tsconfig.json` はこれらを書かずに既定値へ依存していた。
しかし、TypeScript 5系を同梱するエディタが同じ `tsconfig.json` を読むと両者は無効として扱われ、エディタには出ないエラーがCIで初めて出る。

`strict` だけでは、配列やRecordの添字アクセスが `undefined` を返しうることや、省略可能なプロパティへの `undefined` の明示代入を検出できない。
到達不能コードと未使用ラベルはOxlintが警告として報告するが、警告では `vp check` が失敗しない。

`include` が `src` だけだったため、`vite.config.ts` は `tsc` の検査対象から外れていた。
`vp check` は推論プロジェクトとして型を検査するものの、このリポジトリの `tsconfig.json` の設定は適用されない。
さらに、`@/*` のエイリアスを `tsconfig.json` の `paths` と `vite.config.ts` の `resolve.alias` の二か所で別々に定義しており、片方だけを変えても検出されなかった。

コードベースが小さい現時点では、追加の検査を有効にしても既存コードに違反はない。

## Decision

`tsconfig.json` に `strict` と `noUncheckedSideEffectImports` を明示する。
加えて `noUncheckedIndexedAccess`、`exactOptionalPropertyTypes`、`noImplicitOverride`、`noImplicitReturns` を有効にし、`allowUnreachableCode` と `allowUnusedLabels` を `false` にしてエラーへ昇格する。

`include` に `vite.config.ts` を加え、`tsc` と `vp check` の両方で同じ設定を使って検査する。
`vite.config.ts` は Node API を使わないため、単一の `tsconfig.json` のまま DOM の lib と `vite-plus/client` の型で検査する。
Node API が必要になった時点で、`tsconfig.app.json` と `tsconfig.node.json` を project references で分ける。

エイリアスの正本は `tsconfig.json` の `paths` とし、Vite には `resolve.tsconfigPaths: true` で読ませる。

対象がない `allowArbitraryExtensions` は削除する。

## Consequences

### Positive

- 添字アクセスと省略可能プロパティに由来する `undefined` の混入を、`tsc` と `vp check` がコンパイル時に検出する。
- エディタが同梱する TypeScript のバージョンに関係なく、厳格モードの判定が一致する。
- `vite.config.ts` の型エラーが本番ビルドの `tsc` でも失敗として扱われる。
- エイリアスの定義が一か所になり、Vite と TypeScript の解決結果が食い違わない。

### Negative

- `noUncheckedIndexedAccess` により、長さを確認済みの配列でも添字アクセスに絞り込みや非nullアサーションが必要になる場合がある。
- `exactOptionalPropertyTypes` は、省略可能プロパティへ `undefined` を渡すライブラリの型やコード生成（ADR-024 の Orval など）と衝突することがある。
  衝突した場合は、生成設定の調整または型の修正で対応し、フラグ全体の無効化は別の ADR で判断する。
- `vite.config.ts` はブラウザ向けの型で検査されるため、`document` のようなブラウザ専用 API を誤って使っても型エラーにならない。

### Neutral

- `noPropertyAccessFromIndexSignature` は、`noUncheckedIndexedAccess` と検出対象が重なるうえ記法の変更を強いるため採用しない。
- `noUnusedLocals` と `noUnusedParameters` はOxlintの規則と重なるが、従来どおり維持する。

## Alternatives Considered

### `@tsconfig/strictest` を継承する

- **Description**：コミュニティが保守する厳格設定のベースを `extends` で読み込む。
- **Pros**：推奨フラグの追加に依存の更新だけで追従できる。
- **Cons**：開発依存が一つ増え、既定で有効な検査の範囲がリポジトリの外で変わる。
  `noPropertyAccessFromIndexSignature` など採用しないフラグも含む。

### project references で app と node を分ける

- **Description**：Vite の公式テンプレートと同じく、`tsconfig.app.json` と `tsconfig.node.json` を作り `tsc -b` で検査する。
- **Pros**：設定ファイルを実行環境に合った lib と型で検査できる。
- **Cons**：設定ファイルが三つに増え、Node API の型のために `@types/node` の追加が必要になる。
  現状の `vite.config.ts` は Node API を使わない。

### 既定値への依存を続ける

- **Description**：TypeScript 6 以降の既定値に任せ、`tsconfig.json` を変えない。
- **Pros**：設定が短い。
- **Cons**：古い TypeScript を使うツールと判定が食い違い、添字アクセスなどの検査は有効にならない。

## References

- [ADR-024: Frontend API client生成にOrvalを採用する](ADR-024-adopt-orval-for-frontend-api-client.md)
- [Announcing TypeScript 6.0](https://devblogs.microsoft.com/typescript/announcing-typescript-6-0/)
- [TSConfig Reference](https://www.typescriptlang.org/tsconfig/)
- [Vite: `resolve.tsconfigPaths`](https://vite.dev/config/shared-options)
