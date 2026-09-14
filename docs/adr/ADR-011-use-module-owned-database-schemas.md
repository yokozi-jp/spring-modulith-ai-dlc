# ADR-011: モジュール所有のデータベーススキーマを使う

## Status

Proposed

## Date

2026-09-14

## Context

Spring Modulith のアプリケーションモジュールは、パッケージ境界によって所有する業務機能を分ける。
一方、当初の PostgreSQL は `demo` スキーマだけを使い、Liquibase と jOOQ、実行時の SQL が接続の既定スキーマに依存していた。
この構成では、将来モジュールを追加してもテーブルの所有境界を DB 上で識別できず、異なるモジュールの同名テーブルも扱えない。

現時点で業務モジュールと業務テーブルは存在せず、Spring Modulith のイベント出版レジストリだけが存在する。
存在しない業務モジュールのスキーマを先に作ると、名前と境界を実装より先に固定してしまう。
まず共有インフラストラクチャを `modulith` スキーマへ分離し、業務スキーマは対応するモジュールと最初のテーブルを追加するときに作る必要がある。

Liquibase はchangesetの実行前に管理テーブルを作るため、その配置先を自身のchangesetでは作成できない。
しかし管理テーブルとイベント出版テーブルを同じ `modulith` スキーマへ置くと、DB初期化とLiquibaseのどちらがモジュールスキーマを管理するかが分かれる。
Liquibase専用の管理スキーマだけを事前作成すれば、すべてのモジュールスキーマをchangesetで管理できる。

## Decision

一つの PostgreSQL データベース内で、各アプリケーションモジュールが同名の物理スキーマを所有する。
Spring Modulith のイベント出版レジストリは、共有インフラストラクチャ用の `modulith` スキーマへ置く。
業務モジュール用スキーマは、対応するモジュールと永続化テーブルを追加するchangesetで作成する。

DB初期化はLiquibase管理テーブル専用の `liquibase` スキーマだけを、マイグレーション用ロールの所有で作成する。
Liquibaseは `liquibase` に一つの変更履歴を持ち、001 changesetから `modulith` を含むモジュールスキーマを作成する。
各changesetは対象の `schemaName` を明示し、接続の既定スキーマによるテーブル配置に依存しない。

jOOQはコードで列挙したモジュールスキーマから生成し、生成コードに物理スキーマ名を残す。
実行時のSQLは生成メタモデルのスキーマ修飾名を使い、PostgreSQLの `search_path` による暗黙の振り分けを行わない。
スキーマ名は環境差を持たないアーキテクチャ上の固定値であるため、`DB_SCHEMA` と `DB_SCHEMAS` は使わない。

アプリケーション用ロールには、各changesetが所有スキーマのUSAGEと所有テーブルのDMLだけを付与する。
Liquibase管理スキーマと管理テーブルへの権限は付与しない。
アプリケーション用とマイグレーション用の二ロール、資格情報の分離、単一DBというADR-009の方針は維持する。

## Consequences

### Positive

- Javaのモジュール境界とPostgreSQLのテーブル所有境界が対応する。
- DB初期化は `liquibase` の作成だけになり、モジュールスキーマの追加とrollbackをLiquibaseへ一本化できる。
- jOOQがスキーマ修飾SQLを生成するため、`search_path` の順序や同名テーブルに依存しない。
- Liquibaseの履歴とデプロイ順序を一つに保ち、既存のタグ運用とrollback検証を維持できる。
- スキーマ設定の環境変数がなくなり、環境間の名前ずれを防げる。

### Negative

- `liquibase` スキーマはLiquibase起動前にDB初期化または環境構築処理で作成する必要がある。
- 001 changesetを初期状態から書き換えるため、適用済みの既存DBは再利用できず、作り直す必要がある。
- スキーマを追加する変更では、Liquibase changesetとjOOQ生成対象の一覧を同時に更新する必要がある。
- changesetがアプリケーション用ロールへ権限を付与するため、マイグレーションジョブにも `DB_USERNAME` を渡す必要がある。
- 初回マイグレーションより前にアプリケーション用ロールが存在している必要がある。`docker/initdb` が作らないステージングと本番では、ロール作成を初回マイグレーションの前提手順にする。

### Neutral

- Liquibase管理テーブルはモジュール別に分割しない。
- Hikariの既定スキーマは `modulith` に固定するが、業務テーブルの振り分けには使わない。
- 将来モジュール間の外部キーを許可するかどうかは、実際のモジュール間データ整合性要件が生じた時点で決める。

## Alternatives Considered

### Alternative 1: Liquibase管理テーブルとイベント出版テーブルを `modulith` に置く

- Description：DB初期化で `modulith` を作り、管理テーブルとイベント出版テーブルをまとめる。
- Pros：現在存在するテーブルを一つのスキーマにまとめられる。
- Cons：最初のモジュールスキーマだけDB初期化が管理し、以後のモジュールスキーマをLiquibaseが管理するという例外が残る。

### Alternative 2: 単一 `demo` スキーマを維持する

- Description：全モジュールのテーブルを一つのスキーマへ置く。
- Pros：現在の設定と生成コードを変更せずに済む。
- Cons：DB上で所有境界を表せず、モジュール追加後も暗黙の既定スキーマへ依存する。

### Alternative 3: モジュールごとにデータベースとDataSourceを分ける

- Description：各モジュールへ独立したPostgreSQLデータベースまたは接続を割り当てる。
- Pros：権限と障害の境界を強く分離できる。
- Cons：現時点のモジュラーモノリスには接続、トランザクション、運用の複雑さが過剰である。

### Alternative 4: モジュール別にLiquibase履歴を分ける

- Description：スキーマごとにmaster changelogと `DATABASECHANGELOG` を持つ。
- Pros：モジュール単位でマイグレーションを実行できる。
- Cons：単一アプリケーションのデプロイ順序、タグ、rollbackの管理が分散し、現行のリリース手順を複雑にする。

## References

- [ADR-001](./ADR-001-adopt-spring-modulith-modular-monolith.md)
- [ADR-003](./ADR-003-adopt-jooq-for-data-access.md)
- [ADR-004](./ADR-004-commit-jooq-generated-code.md)
- [ADR-005](./ADR-005-decouple-liquibase-from-app-startup.md)
- [ADR-009](./ADR-009-unify-db-connection-target-credentials-only-role-split.md)
- `backend/gradle/database.gradle`
- `backend/src/main/resources/db/changelog/`
- `docker/initdb/`
