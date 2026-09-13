# ADR-004: jOOQ 生成コードを Git 管理する

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済みの決定を遡って記録した（backfill）。

jOOQ の生成コードをビルド時に毎回生成する方式は、
ビルドが稼働 DB へ依存し、CI・本番デプロイで DB 接続とスキーマ整合を要求する。
生成物とスキーマ変更の対応関係を、レビューと履歴で追える形にしたい。

## Decision

jOOQ 生成コードを Git 管理し、changeset と同じ変更としてコミットする。

- スキーマ変更（Liquibase changeset）と jOOQ 再生成を 1 つの変更としてまとめる。
- 本番の DB マイグレーションとアプリケーションデプロイでは jOOQ 生成を実行しない。
- 生成は開発または CI の使い捨て DB に対してだけ行う。

## Consequences

### Positive

- ビルドが稼働 DB に依存しない。
- 生成物の差分がレビューでき、スキーマ変更との対応が履歴に残る。
- 本番デプロイに不要な DB メタデータ参照権限を持ち込まない。

### Negative

- 生成コードがリポジトリに含まれ、差分が大きくなることがある。
- changeset 追加時に再生成を忘れないための手順・規律が要る。

### Neutral

- 生成コードは静的解析とフォーマットの対象外にする。

## Alternatives Considered

### Alternative 1: ビルド時に毎回生成（Git 管理しない）

- 説明：CI・ローカルのビルドで都度生成する。
- Pros：生成物をコミットしない。
- Cons：ビルドが稼働 DB に依存し、差分レビューできない。

### Alternative 2: テストコンテナから生成

- 説明：ビルド中に一時 DB を立てて生成する。
- Pros：外部 DB に依存しない。
- Cons：ビルド時間が伸び、生成物の差分は依然レビューできない。

## References

- `backend/gradle/database.gradle`
- [`docs/database-migrations.md`](../database-migrations.md)
- [`ADR-003-adopt-jooq-for-data-access.md`](ADR-003-adopt-jooq-for-data-access.md)
