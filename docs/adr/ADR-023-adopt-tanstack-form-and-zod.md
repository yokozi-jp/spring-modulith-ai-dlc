---
type: Architecture Decision Record
title: 'ADR-023: TanStack Form と Zod を採用する'
description: フォームと API 生成の schema を一系統に揃えるため、TanStack Form と Zod を採用する決定。
tags: [adr, frontend, form, validation]
---

# ADR-023: TanStack Form と Zod を採用する

## Status

Proposed

## Date

2026-09-29

## Context

Frontend は React 19 と TanStack Router を採用しているが、フォーム状態と入力検証を扱う共通ライブラリを持っていない。 現在の画面に業務フォームはなく、標準化する対象は今後追加するフォームである。

フォームでは入力値、送信状態、項目単位のエラーを型安全に管理する必要がある。 入力検証はTypeScriptの型だけでは実行時に働かないため、ブラウザで実行できるschemaが必要になる。 フォームライブラリと検証ライブラリを独自adapterで接続すると、そのadapterの保守と型の不一致を追加で引き受けることになる。

TanStack Form 1.x は Standard Schema v1 を実装するschemaをvalidatorとして受け取る。 Zod 4.x も Standard Schema v1 に対応しているため、専用adapterを追加せず連携できる。 OrvalはOpenAPIからZod schemaを生成し、Fetch clientのruntime response validationに利用できる。 フォームとAPI生成でZodへ揃えれば、schema記法、エラー型、依存更新を一系統にできる。

## Decision

Reactのフォーム状態管理に `@tanstack/react-form` 1.xを使い、実行時入力検証にZod 4.xを使う。 依存バージョンは `frontend/package.json` へ固定し、TanStack FormのvalidatorへZod schemaをStandard Schemaとして直接渡す。

OrvalでAPI clientを生成するときもZod schemaを生成し、runtime response validationが必要な境界で利用する。 共通field componentやschema wrapperは先行して作らず、最初の業務フォームで重複が確認できた場合に限り共有部品を抽出する。

## Consequences

### Positive

- フォームの値、項目状態、送信処理をReact向けの型付きAPIで管理できる。
- Zod schemaをadapterなしでTanStack Formの検証に使える。
- OrvalがOpenAPIから生成するschemaと同じ検証ライブラリを使える。
- フォームとAPIでschema記法、エラー処理、依存更新を一系統にできる。

### Negative

- React、TanStack Form、Zodの更新に追従する必要がある。
- TypeScriptの型に加えてZod schemaを保守する必要がある。
- ライブラリを追加しても、アクセシブルなlabel、エラー表示、focus移動は各フォームで実装する必要がある。
- Valibotを使う構成よりbundle sizeが増える可能性がある。

### Neutral

- 現時点では業務フォームがないため、依存追加だけを行い、利用例のための画面や抽象化は追加しない。
- HTTP transportとOpenAPI client生成の方針はADR-024で定める。
- up-fetchは追加しない。

## Alternatives Considered

### TanStack FormとValibotを採用する

- **Description**：フォーム状態にTanStack Form、実行時検証にValibotを使う。
- **Pros**：Valibotは機能単位のimportとtree shakingを前提とし、bundleを小さくしやすい。
- **Cons**：Orvalのruntime validationで生成するZodと二つのschema libraryを保守することになる。

### Reactのstateだけでフォームを管理する

- **Description**：`useState` と標準のFormDataで各フォームを実装する。
- **Pros**：依存を追加せず、小規模なフォームではコード量を抑えられる。
- **Cons**：項目状態、非同期検証、送信状態をフォームごとに実装すると重複が増える。

### React Hook FormとZodを採用する

- **Description**：React Hook Formで状態を管理し、Zod schemaをresolver経由で接続する。
- **Pros**：利用例と周辺ライブラリが多い。
- **Cons**：既存のTanStack系ライブラリと異なる状態管理APIが増え、schema接続に追加adapterが必要になる。

## References

- [ADR-024: Frontend API client生成にOrvalを採用する](ADR-024-adopt-orval-for-frontend-api-client.md)
- [TanStack Form: Standard Schema example](https://tanstack.com/form/v1/docs/framework/react/examples/standard-schema)
- [Orval: Zod](https://orval.dev/docs/guides/zod/)
- [Zod](https://zod.dev/)
- [frontend/package.json](../../frontend/package.json)
