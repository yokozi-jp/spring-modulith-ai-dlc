# ADR-003: データアクセスに jOOQ を採用

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済みの決定を遡って記録した（backfill）。

型安全な SQL と、PostgreSQL 固有の機能・方言をそのまま活かせるデータアクセスがほしい。
ORM の暗黙のフェッチや N+1、マッピングの不透明さを避け、
発行される SQL を明示的に制御したい。
また、日時規約（絶対時刻は `Instant`）へ素直にマッピングできることが要る。

## Decision

データアクセスに jOOQ を採用する（`spring-boot-starter-jooq`）。

- コード生成は公式の `org.jooq.jooq-codegen-gradle` プラグインを使う。
- PostgreSQL の `TIMESTAMP WITH TIME ZONE` を Java の `Instant` へマッピングする。
- 生成は開発または CI の使い捨て DB に対して行い、本番 DB では実行しない。

## Consequences

### Positive

- コンパイル時に検査される型安全な SQL を書ける。
- 発行される SQL が明示的で、性能を制御しやすい。
- PostgreSQL の方言・機能を素直に使える。

### Negative

- スキーマ変更時にコード生成の手順が要る（ADR-004 参照）。
- ORM 的な自動永続化・遅延ロードは得られない。

### Neutral

- 生成コードは手書きコードと区別し、静的解析（PMD / SpotBugs / ArchUnit）から除外する。

## Alternatives Considered

### Alternative 1: JPA / Hibernate

- 説明：ORM でエンティティを永続化する。
- Pros：定型 CRUD の記述が少ない。
- Cons：暗黙のフェッチ・N+1・発行 SQL の不透明さ。ドメインと永続化の兼用を招きやすい。

### Alternative 2: Spring Data JDBC

- 説明：軽量な集約永続化。
- Pros：単純で明快。
- Cons：複雑なクエリの型安全な表現力が jOOQ に劣る。

### Alternative 3: 生の JDBC / MyBatis

- 説明：SQL を文字列で書く。
- Pros：完全な制御。
- Cons：型安全性がなく、リファクタリングでスキーマ不整合を検出できない。

## References

- `backend/build.gradle`（`spring-boot-starter-jooq`、`jooq-codegen-gradle`）
- [`../../.kiro/steering/datetime-timezone-conventions.md`](../../.kiro/steering/datetime-timezone-conventions.md)
- [`ADR-004-commit-jooq-generated-code.md`](ADR-004-commit-jooq-generated-code.md)
