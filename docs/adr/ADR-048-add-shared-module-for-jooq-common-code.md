---
type: ADR
title: 'ADR-048: jOOQ の共通処理を共有モジュール shared に置く'
description: 共通カラムの値の設定、NULL から空文字への変換、楽観的ロックの更新件数の判定という jOOQ の共通処理を、Spring Modulith の shared モジュール com.example.demo.shared の infrastructure.persistence に置く決定。
tags: [adr, backend, spring-modulith, jooq, database]
---

# ADR-048: jOOQ の共通処理を共有モジュール shared に置く

## Status

Proposed

## Date

2026-10-03

## Context

複数の機能モジュールが、同じ jOOQ の処理を必要とする。
INSERT と UPDATE で共通カラムに値を設定する処理（[PostgreSQL の共通カラム](../database/postgresql-common-columns.md)）がその一つである。
NULL を空文字 `''` へ一律に変える Converter（[PostgreSQL のデータ型](../database/postgresql-data-types.md)）も、モジュールごとに書くものではない。
楽観的ロックの UPDATE の更新件数の判定（[PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)）も、どの集約の `update` にも要る。

ArchUnit のオニオンの規則は、ベースパッケージ `com.example.demo` 直下以外のすべてのクラスに、どれかの層のパッケージへ属することを求める。
同じ規則は、jOOQ の利用を `..infrastructure.persistence..` だけに許す。
そのため、ベースパッケージ直下に置いたコードは jOOQ を使えない。

一方、[バックエンドアーキテクチャ](../backend/architecture.md) の「全体設定」は、共有される概念と安定した境界が明確になるまで共通パッケージを作らないとしている。
今回は、どの機能モジュールにも同じ規則（共通カラム、空文字の扱い、楽観的ロック）が課されるため、共有する範囲が規約で決まっている。

業務テーブルと main の jOOQ のコードはまだない。
そのため、共通処理は既存のコードから抜き出すのではなく、使われる前に設計する。

## Decision

アプリケーションモジュール `com.example.demo.shared` を作り、`@Modulithic(sharedModules = "shared")` で Spring Modulith の shared モジュールにする。
shared モジュールは、モジュール単位の統合テストでも常に起動される。

jOOQ の共通処理は `com.example.demo.shared.infrastructure.persistence` に置く。
このパッケージはモジュールのルートではないため、[バックエンドアーキテクチャ](../backend/architecture.md) の定めに従い `@NamedInterface` で公開する。

`shared` には、共通カラムの値を組み立てる共通処理、NULL を空文字へ変える Converter、楽観的ロックの更新件数を判定する共通処理だけを置き、業務の概念を置かない。
共通カラムの値には `lock_no` を含め、INSERT では `1`、UPDATE では1加算した値にする。
`*_pgm_cd` の値の求め方は [ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md) で決める。
ADR-051 の `*_pgm_cd` を束縛する Aspect も、共通カラムの値を組み立てる共通処理の一部として `shared` に置く。

楽観的ロックの更新件数を判定する共通処理 `OptimisticLock` も `shared` に置く（[ADR-052](ADR-052-detect-optimistic-lock-conflicts-by-update-count.md)）。
`shared` は Domain と `error` の型に依存せず、競合の例外は呼び出し側が関数で渡す。
`shared` を使うのは、他のモジュールの `infrastructure.persistence` のアダプターだけとする。

生成クラスの `CREATED_*`、`UPDATED_*`、`PATCHED_*` のフィールドを参照してよいのは、`shared` の共通処理だけとする。
楽観的ロックで各モジュールが参照する `LOCK_NO` は、この制限から除く。

## Consequences

### Positive

- 共通カラムの設定と楽観的ロックの更新件数の判定の実装が一つになり、モジュールごとに実装が食い違わない。
- 共通カラムを参照できる場所が一つに限られるため、業務ロジックから共通カラムを参照していないかを ArchUnit で検査できる。

### Negative

- `shared` の変更がすべての機能モジュールに影響する。
- `shared` に何でも置かれ、境界のない共通パッケージになるおそれがある。置くものを永続化の技術的な共通処理に限ることで抑える。

### Neutral

- モジュールを作る変更で、[バックエンドアーキテクチャ](../backend/architecture.md) と [バックエンドのアーキテクチャテスト](../backend/architecture-tests.md) を更新する。

## Alternatives Considered

### 選択肢1: 各機能モジュールに同じ処理を書く

- **Description**：共通カラムの設定と楽観的ロックの更新件数の判定を、各モジュールの `infrastructure.persistence` に書く。
- **Pros**：モジュール間の依存が増えず、全体設定の方針をそのまま守れる。
- **Cons**：同じ処理がモジュールの数だけ増え、規約の変更をすべてのモジュールへ反映する必要がある。共通カラムの参照を一か所に限れない。

### 選択肢2: ベースパッケージ直下に置く

- **Description**：共通処理を `com.example.demo` 直下に置く。
- **Pros**：Spring Modulith のモジュールを増やさずに済む。
- **Cons**：jOOQ を `..infrastructure.persistence..` の外で使うことになり、ArchUnit の規則に違反する。

### 選択肢3: OPEN のアプリケーションモジュールにする

- **Description**：`@ApplicationModule(type = Type.OPEN)` を付けたモジュールに置く。
- **Pros**：`@NamedInterface` を付けずに、どのパッケージも他のモジュールから参照できる。
- **Cons**：内部のパッケージがすべて公開され、公開する範囲を限れない。Spring Modulith のリファレンスも、OPEN のモジュールは分割がうまくいっていない兆候であることが多いとしている。

### 選択肢4: 別の Gradle サブプロジェクトかライブラリにする

- **Description**：共通処理を別の Gradle サブプロジェクトや配布するライブラリに切り出す。
- **Pros**：依存の方向をビルドの単位で強制できる。
- **Cons**：一つのアプリケーションのための構成として重く、ビルドとリリースの手間が増える。

## References

- [ADR-001: Spring Modulith によるモジュラーモノリス](ADR-001-adopt-spring-modulith-modular-monolith.md)
- [ADR-002: package by feature とオニオンアーキテクチャ](ADR-002-package-by-feature-onion-architecture.md)
- [ADR-003: データアクセスに jOOQ を採用](ADR-003-adopt-jooq-for-data-access.md)
- [ADR-051: 共通カラムの pgm_cd を Aspect と ScopedValue で渡す](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)
- [ADR-052: 楽観的ロックの競合を UPDATE の条件の lock_no と更新件数で判定する](ADR-052-detect-optimistic-lock-conflicts-by-update-count.md)
- [バックエンドアーキテクチャ](../backend/architecture.md)
- [バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)
- [PostgreSQL の共通カラム](../database/postgresql-common-columns.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [Spring Modulith `@Modulithic`](https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/Modulithic.html)
- [Spring Modulith: Open Application Modules](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
