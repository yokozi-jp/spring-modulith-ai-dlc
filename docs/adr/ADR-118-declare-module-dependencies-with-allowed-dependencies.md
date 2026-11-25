---
type: ADR
title: 'ADR-118: モジュールの依存先を allowedDependencies で宣言する'
description: モジュール間の依存の向きを、手書きのテストではなく各モジュールの package-info.java の allowedDependencies で持ち、宣言の必須だけをテストで確かめる決定。
tags: [adr, architecture, spring-modulith, architecture-test]
---

# ADR-118: モジュールの依存先を allowedDependencies で宣言する

## Status

Proposed

## Date

2026-11-23

## Context

機能モジュールに `@ApplicationModule` を付けない形では、Spring Modulith はどのモジュールも依存先を宣言していないものとして扱う。
`ApplicationModules.verify()` は循環と内部の型への依存しか止めず、`payment` から `product` のような循環にならない依存を検出できない。
向きは手書きのテスト `featureModulesDependOnlyInDeclaredDirections` が、手で持つ一覧 `FEATURES` のモジュールだけについて確かめていた。
`FEATURES` に足し忘れた新しいモジュールは、その依存先もそのモジュールへの依存も比べる対象から外れた。

[#340](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/340) の調査で、`yokozi-jp/agent-software-factory` の ADR 0026 が、モジュールが依存してよいモジュールをモジュール自身が宣言する形を取ると分かった。
こちらでは同じ役割を `@ApplicationModule(allowedDependencies = ...)` で持てる。

`@Modulithic(sharedModules = "shared")` で暗黙に許可先に入るのは `shared` のルートだけで、名前付きインタフェースは入らない（Spring Modulith 2.1.2 の `ApplicationModule#getAllowedDependencies` と `AllowedDependency.to`）。

## Decision

各モジュールのルートの `package-info.java` に `@ApplicationModule(allowedDependencies = ...)` を書き、依存先をモジュール自身が宣言する。
現在の値の正本は各 `package-info.java` で、次の表は決定時のものである。
`@NamedInterface` は機能モジュールに付けない。

| モジュール | `allowedDependencies`                       |
| ---------- | ------------------------------------------- |
| `product`  | `{"shared :: security", "jooq"}`            |
| `ordering` | `{"product", "shared :: *", "jooq"}`        |
| `payment`  | `{"ordering", "shared :: *", "jooq"}`       |
| `tenancy`  | `{"shared :: *", "jooq"}`                   |
| `error`    | `"shared :: *"`                             |
| `shared`   | `"jooq"`                                    |

`"shared :: *"` は `shared` の名前付きインタフェースをすべて許可する。
暗黙の許可先は `shared` のルートだけなので、名前付きインタフェースを使うモジュールは明示する。

`featureModulesDependOnlyInDeclaredDirections` と `FEATURES` を消す。
代わりに `ApplicationModuleArchitectureTest` へ、OPEN 以外のすべてのモジュールが `allowedDependencies` を既定の `OPEN_TOKEN` 以外の値で宣言していることを確かめるテストを 1 つ置く。
Spring Modulith には宣言のないモジュールを違反にする設定がなく、`VerificationOptions` は ArchUnit の規則を足せるだけなので、このテストだけを手で書く。

`sharedModuleIsUsedOnlyByPersistenceAdapters` と `persistenceAdaptersUseOnlyOwnGeneratedSchema` は残す。
`allowedDependencies` はモジュールの単位の宣言で、モジュールの中の層と、モジュールの名前とスキーマの名前が同じこと（[ADR-094](ADR-094-switch-to-module-database-roles.md)）を表せないためである。
[ADR-067](ADR-067-open-jooq-generated-module.md) は変えない。スキーマごとの名前付きインタフェースは取り込まない。

## Consequences

### Positive

- 新しいモジュールは、宣言を書かないとテストが失敗するので、比べる対象から外れない。
- 依存の向きの正本がモジュールの `package-info.java` になり、手で持つ一覧がなくなる。
- 宣言にない依存は `verify()` が `Module 'payment' depends on module 'product'` の形で止める。

### Negative

- 新しいモジュールは `package-info.java` に宣言を書く手間が増える。
- 宣言を広げる変更（たとえば `payment` に `"product"` を足す）は、どのテストも失敗にしない。レビューだけが止める。
- `shared` の名前付きインタフェースを使うモジュールは、`"shared :: *"` を書き忘れると `verify()` が失敗する。

### Neutral

- 宣言の必須を確かめるテストは手書きで残る。
- 宣言は `spring.modulith.runtime.verification-enabled` により、起動時の検証でも効く。
- `sharedModules` の暗黙の許可の範囲は Spring Modulith の版で変わりうる。

## Alternatives Considered

### 選択肢1: 手書きのテストを残す

- **Description**：`FEATURES` を手で持ち、向きを比べるテストを続ける。
- **Pros**：変更がない。
- **Cons**：足し忘れたモジュールが検査から外れる。

### 選択肢2: スキーマごとの名前付きインタフェースを jOOQ の生成物に付ける

- **Description**：agent-software-factory に近い形で、依存の単位をスキーマまで細かくする。
- **Pros**：スキーマの依存も宣言で持てる。
- **Cons**：[#340](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/340) で、`persistenceAdaptersUseOnlyOwnGeneratedSchema` が残るため取り込まないと決めた。

## References

- [ADR-001](ADR-001-adopt-spring-modulith-modular-monolith.md)
- [ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-067](ADR-067-open-jooq-generated-module.md)
- [ADR-094](ADR-094-switch-to-module-database-roles.md)
- `backend/src/test/java/com/example/demo/architecture/ApplicationModuleArchitectureTest.java`
- [バックエンドアーキテクチャ](../backend/architecture.md)
