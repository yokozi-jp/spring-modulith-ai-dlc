# ADR-005: Liquibase をアプリケーション起動から分離する

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済みの決定を遡って記録した（backfill）。

アプリケーション起動時に自動でマイグレーションを走らせると、
複数インスタンスの同時起動で競合し、デプロイとスキーマ変更のタイミングを制御できない。
本番では、後方互換なスキーマ変更（expand-and-contract）を、
アプリケーションデプロイと独立した明示的な手順で適用したい。

## Decision

Liquibase の起動時自動実行を無効化し（`spring.liquibase.enabled: false`）、
マイグレーションをデプロイ前の明示コマンドで適用する。

- ローカル・CI は `make be-migrate`（`./gradlew migrateDatabase`）で適用する。
- スキーマタグを Git 管理し、本番は `make be-release-migrate` で固定タグまで適用する。
- 使い捨て DB で update → rollback → 再 update とタグを検証する
  （`make be-verify-migrations`）。
- 本番は expand-and-contract を守り、旧・新アプリの併存中も後方互換にする。

## Consequences

### Positive

- デプロイとマイグレーションのタイミングを独立に制御できる。
- 複数インスタンス起動時のマイグレーション競合を避けられる。
- rollback とスキーマタグを検証でき、切り戻しの対象を履歴から選べる。

### Negative

- 初回起動前と changeset 追加後に、マイグレーションの明示実行が必要。
- マイグレーション専用の資格情報とデプロイ順序の運用が要る。

### Neutral

- アプリケーション用 DB ロールとマイグレーション用ロールを分ける。

## Alternatives Considered

### Alternative 1: 起動時自動マイグレーション（Spring Boot 既定）

- 説明：アプリ起動時に Liquibase を自動実行する。
- Pros：手順が要らず単純。
- Cons：複数インスタンスで競合し、デプロイ順序と切り戻しを制御できない。

### Alternative 2: Flyway

- 説明：別のマイグレーションツール。
- Pros：単純な SQL 中心。
- Cons：本プロジェクトが使う rollback・タグ運用・changeset の表現力で Liquibase を選好。

## References

- `backend/src/main/resources/application.yaml`（`spring.liquibase.enabled: false`）
- [`docs/database-migrations.md`](../database-migrations.md)
- `Makefile`（`be-migrate` / `be-release-migrate` / `be-verify-migrations`）
