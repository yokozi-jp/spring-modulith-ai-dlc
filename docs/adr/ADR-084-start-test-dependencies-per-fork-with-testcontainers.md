---
type: ADR
title: 'ADR-084: バックエンドのテストの依存を Testcontainers で fork ごとに起動し、テストを並列に実行する'
description: バックエンドのテストが共有の compose-test に頼るのをやめ、テストの JVM ごとに Testcontainers で PostgreSQL と Redis、プロセス内で WireMock を起動し、Gradle の fork を並列にし、カバレッジの検証を test から check へ移す決定。
tags: [adr, testing, backend, database, tooling]
---

# ADR-084: バックエンドのテストの依存を Testcontainers で fork ごとに起動し、テストを並列に実行する

## Status

Proposed

## Date

2026-11-16

## Context

バックエンドのテストは、`docker/compose-test.yml` の PostgreSQL 5433、Redis 6380、WireMock 8082 を、テストの外で起動して使っていた。
`task test` が compose を起動し、Gradle の `migrateDatabase` で changeset を適用してから、1 つの JVM で全テストを順に実行していた。

この構成には次の問題があった。

- **並列にできない。** テストは 1 つの DB を共有し、`CleanGeneratedTablesExtension` はテストごとに生成した全テーブルを消す。`EventPublicationResubmitterOutcomesTest` は `event_publication` を全件消し、`FixtureTablesExtension` は固定の `fixture` スキーマを作り直す。Gradle の `maxParallelForks` を上げると、fork どうしが互いのデータを消す。
- **CPU を使い切れない。** 4 コアの WSL で、1 つの JVM の全テストに約 400 秒かかった（942 件、80 クラス）。
- **状態が残る。** フックを途中で止めると compose のコンテナと DB が残る。次の `be-verify-migrations` が残った履歴から rollback を始め、`cannot drop schema modulith` で失敗した。
- **`./gradlew test` だけでは動かない。** テストの依存の起動、`.env.test` の読み込み、マイグレーションを Taskfile に頼り、IDE から実行するには手で compose を起動する必要があった。
- **`test --tests` で絞ると必ず失敗する。** `test` に `finalizedBy jacocoTestCoverageVerification` があり、一部のテストではクラスごとの下限を割る。このため、手順書は `-x jacocoTestCoverageVerification` を付け、`exportOpenApi` という別の Test タスクも必要だった（[ADR-064](ADR-064-write-openapi-contract-from-test-task-in-ci.md)）。

Spring Boot 3.1 以降は、テストの依存を Testcontainers でテストの JVM が起動するのがデファクトである。
このリポジトリのテストは、接続先を `application.yaml` のプレースホルダの名前（`DB_HOST`、`MIGRATION_DB_USERNAME` など）で読む。`FixtureTablesExtension`、`ShedLockSessionTimeZoneTest`、`DemoApplicationTest` は `spring.datasource.url` などを `Environment` から読む。
テストの設定ファイルは置かず、差分は `.env.test` と環境変数で与える（`ConfigFileLayoutTest`）。

## Decision

バックエンドのテストの依存を、テストの JVM ごとに起動し、Gradle の fork を並列にする。

- **起動するもの。** テストの JVM ごとに、Testcontainers で PostgreSQL と Redis のコンテナを 1 つずつ起動し、決済代行の WireMock をプロセス内で起動する（`testkit.InfrastructureContainers`）。
- **起動の契機。** `META-INF/spring.factories` に登録した `ContextCustomizerFactory` が、最初の Spring のテストのコンテキストを作るときに起動する。Spring を使わないテストだけの fork では起動しない。
- **接続先の渡し方。** 接続先は、`application.yaml` のプレースホルダの名前で、最も優先する property source としてコンテキストへ足す。環境変数から緩い束縛で直接つながる `payment-gateway.base-url` は、束縛先の名前でも渡す。
- **イメージとコマンド。** `docker/compose-test.yml` から読み、E2E とスモークの依存と版をそろえる。初期化のスクリプトは `docker/initdb` を同じく使う。使い捨ての DB に限り `fsync=off` を足す。
- **マイグレーション。** テストの JVM が、Liquibase で `gradle.properties` の `databaseSchemaTag` まで適用する。`-PtestAllChangesets` を付けると、作りかけを含む全 changeset を適用する。
- **並列度。** `maxParallelForks` は既定でコア数の半分にし、`-PtestForks=<n>` で変える。
- **カバレッジの検証。** `jacocoTestCoverageVerification` を `test` の `finalizedBy` から外し、`check` にぶら下げる。`task be-test` は `test jacocoTestCoverageVerification` を実行する。
- **compose-test に残すもの。** `be-verify-migrations` の rollback の検証、`task e2e`、バックエンドのスモークは、これまでどおり compose-test を使う。`task test` は rollback の検証のためだけに compose の PostgreSQL を起動する。
- **Taskfile の簡素化。** `task test-dev`、`task mutation-test`、`task api-gen` は compose を起動しない。

## Consequences

### Positive

- fork ごとに DB が分かれ、表を消すテストがあっても並列に実行できる。
  4 コアの WSL で 2 つの fork にすると、全テスト（943 件）の実行は約 400 秒から約 212 秒になった。`task be-test` の全体は約 231 秒で、以前の `task test` は compose の起動とマイグレーションを含めて約 533 秒だった。
- 依存がテストの JVM とともに起動して片付く（Ryuk）。止めたフックの状態が次の実行に残らない。
- `./gradlew test --tests <クラス>` が、compose の起動と `-x jacocoTestCoverageVerification` なしで動く。
- PIT の minion も自分の依存を起動するため、`threads` を上げる余地ができる。

### Negative

- fork ごとに PostgreSQL と Redis のコンテナを起動し、changeset を適用するため、fork を増やすほど起動の時間とメモリが増える。
- テストの実行に Docker が必須になる。`docker` が無い環境で pre-push の `be-test` を飛ばす扱いは、これまでと変わらない。
- 接続先を property source で渡すため、環境変数の緩い束縛で直接つながる設定を足したときは、束縛先の名前も渡す必要がある。

### Neutral

- rollback の検証（`updateTestingRollback`）は Liquibase の Gradle プラグインで実行し、compose の PostgreSQL を使い続ける。テストの JVM へ移すかは別に判断する。
- 変更のないモジュールのテストを飛ばす Spring Modulith の `spring-modulith-junit` は、この ADR の範囲外とし、別に判断する。

## Alternatives Considered

### 選択肢1: `@ServiceConnection` で接続先を渡す

- **Description**：`@TestConfiguration` に `@ServiceConnection` を付けたコンテナの Bean を置き、Spring Boot の `ConnectionDetails` で接続する。
- **Pros**：Spring Boot の推奨の書き方で、接続の設定を書かずに済む。
- **Cons**：`ConnectionDetails` は `spring.datasource.url` などの property を設定しないため、それを `Environment` から読む `FixtureTablesExtension`、`ShedLockSessionTimeZoneTest`、`DemoApplicationTest` が動かない。マイグレーションロールの資格情報も渡らない。`@JooqTest` のスライスと `@ApplicationModuleTest` にも同じ構成を足す必要がある。

### 選択肢2: 1 つのコンテナを共有し、fork ごとに DB を複製する

- **Description**：コンテナを 1 つだけ起動し、マイグレーション済みの DB を `CREATE DATABASE ... TEMPLATE` で fork ごとに複製する。
- **Pros**：コンテナの起動とマイグレーションが 1 回で済む。
- **Cons**：fork の JVM どうしでコンテナを共有する仕組み（Testcontainers の再利用か、Gradle の外での起動）が要る。再利用は実験的な機能で、CI での利用は推奨されない。`docker/initdb` の `ALTER ROLE ... IN DATABASE` の時刻帯の設定は複製した DB に引き継がれない。

### 選択肢3: compose-test を残し、fork ごとに DB 名を分ける

- **Description**：`docker/initdb` で `demo_w1`〜`demo_wN` を作り、Gradle の `org.gradle.test.worker` で DB を選ぶ。
- **Pros**：コンテナの起動が増えない。
- **Cons**：compose の起動と片付けをテストの外に残すため、状態が残る問題と `./gradlew test` だけで動かない問題が解けない。fork の数だけ DB をマイグレーションする仕組みを Gradle 側に作る必要がある。

### 選択肢4: JUnit の並列実行を使う

- **Description**：1 つの JVM の中で、JUnit Jupiter の `junit.jupiter.execution.parallel.enabled` でテストクラスを並列にする。
- **Pros**：JVM とコンテナが 1 つで済む。
- **Cons**：同じ DB を共有するため、表を消すテストの衝突が解けない。Spring のコンテキストのキャッシュと固定の時計も、テストクラスの間で共有される。

## References

- [バックエンドのテストの実行基盤](../backend/testing-infrastructure.md)
- [バックエンドのDBテスト](../backend/testing-database.md)
- [ADR-064: OpenAPI 契約を test タスクで書き出し、Backend CI のテスト二重実行をやめる](ADR-064-write-openapi-contract-from-test-task-in-ci.md)
- [ADR-076: PIT を必須でない別のワークフローで変更ごとに実行し、coverage の下限に line とクラスやファイルごとの下限を加える](ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)
- [Spring Boot: Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Gradle: Test#maxParallelForks](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html#org.gradle.api.tasks.testing.Test:maxParallelForks)
