---
type: ADR
title: 'ADR-085: バックエンドのテストの依存を Testcontainers で fork ごとに起動し、テストを並列に実行する'
description: バックエンドのテストが共有の compose-test に頼るのをやめ、テストの JVM ごとに Testcontainers で PostgreSQL と Redis、プロセス内で WireMock を起動し、Gradle の fork を並列にし、カバレッジの検証を test から check へ移す決定。
tags: [adr, testing, backend, database, tooling]
---

# ADR-085: バックエンドのテストの依存を Testcontainers で fork ごとに起動し、テストを並列に実行する

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

Pull Request のレビューで Decision を書き直した。

- `.env.test` の export が残ると、接続先の名前がずれたときに、テストが compose の PostgreSQL へ黙ってつながった。
- 必須の CI からスキーマタグの宣言の検証が抜けた。
- WireMock をプロセス内で起動すると、版とオプションを compose と手で合わせる必要があった。

## Decision

バックエンドのテストの依存を、テストの JVM ごとに起動し、Gradle の fork を並列にする。

- **起動するもの。** テストの JVM ごとに、Testcontainers で PostgreSQL、Redis、決済代行の WireMock のコンテナを 1 つずつ起動する（`testkit.InfrastructureContainers`）。
  ポートは compose と同じく `127.0.0.1` だけで公開する。
- **起動の契機。** `META-INF/spring.factories` に登録した `ContextCustomizerFactory` が、最初の Spring のテストのコンテキストを作るときに起動する。Spring を使わないテストだけの fork では起動しない。
- **接続先の渡し方。** 接続先は、`application.yaml` のプレースホルダの名前で、最も優先する property source としてコンテキストへ足す。
- **接続先以外の設定。** Gradle の `test` と PIT は、`.env.test` を `spring.config.import` のシステムプロパティで読ませる。Taskfile は `.env.test` を環境変数として export しない。
- **イメージとコマンド。** `docker/compose-test.yml` から読み、E2E とスモークの依存と版をそろえる。PostgreSQL の初期化は `docker/initdb` を、WireMock のスタブは `docker/wiremock/mappings` を使う。Ryuk のイメージは digest で固定する。
- **マイグレーション。** テストの JVM が、Liquibase で `gradle.properties` の `databaseSchemaTag` まで適用する。`-PtestAllChangesets` を付けると、作りかけを含む全 changeset を適用する。
- **スキーマタグの宣言の検証。** テストは `migrateDatabase` を通らないため、`verifyDatabaseMigrations` が `validateCurrentSchemaTag` も実行する。
- **並列度。** `maxParallelForks` は既定でコア数の半分にし、`-PtestForks=<n>` で変える。
- **カバレッジの検証。** `jacocoTestCoverageVerification` を `test` の `finalizedBy` から外し、`check` にぶら下げる。`task be-test` は `test jacocoTestCoverageVerification` を実行する。
- **契約の単体の再生成。** `exportOpenApi` を消し、`test --tests com.example.demo.OpenApiContractTest -PopenapiExport=true` で再生成する。[ADR-064](ADR-064-write-openapi-contract-from-test-task-in-ci.md) が `exportOpenApi` を残した理由（`finalizedBy` で一部のテストが失敗すること）がなくなったためである。
- **compose-test に残すもの。** `be-verify-migrations` の rollback の検証、`task e2e`、バックエンドのスモークは、これまでどおり compose-test を使う。
  `task test` は、起動の前に前の回の compose-test を片付け、rollback の検証の後、テストの前に止める。テストが `.env.test` の接続先へ黙ってつながらないようにするためである。
  Backend CI は、rollback の検証を `migrations` ジョブに分け、テストのジョブと並列に走らせる。テストのジョブは compose を使わない。
- **Taskfile の簡素化。** `task test-dev` と `task mutation-test` は `be-test-dev` と `be-mutation-test` の別名にし、`task api-gen` とともに compose を起動しない。使われていない `test-deps-up` と `test-deps-down` を消す。
- **テストのコンテナの禁止。** `testkit` の外のテストは Testcontainers の型を使わない。`TestConventionsArchTest` が検査する。

## Consequences

### Positive

- fork ごとに DB が分かれ、表を消すテストがあっても並列に実行できる。
  4 コアの WSL で 2 つの fork にすると、全テスト（943 件）の実行は約 400 秒から約 212 秒になった。`task be-test` の全体は約 231 秒で、以前の `task test` は compose の起動とマイグレーションを含めて約 533 秒だった。
- テストの依存がテストの JVM とともに起動して片付く（Ryuk）。`task test` の rollback の検証も、前の回の compose-test を片付けてから始める。
- `./gradlew test --tests <クラス>` が、compose の起動、`.env.test` の export、`-x jacocoTestCoverageVerification` なしで動く。
- PIT の minion も自分の依存を起動するため、`threads` を上げる余地ができる。

### Negative

- fork ごとに 3 つのコンテナを起動し、changeset を適用するため、fork を増やすほど起動の時間とメモリが増える。
- GitHub Actions の 4 vCPU の runner では 2 つの fork になり、速くならなかった。`:test` は約 103 秒から約 114 秒、テストのジョブ全体は 3 分 53 秒から 4 分 52 秒になった。コンテナの起動とイメージの取得が、並列の分を打ち消している。
- テストの実行に Docker が必須になる。`docker` が無い環境で pre-push の `be-test` を飛ばす扱いは、これまでと変わらない。
- `task be-test-dev`（`task test-dev`）は、作りかけの changeset で動かすため、カバレッジを検証しない。

### Neutral

- rollback の検証（`updateTestingRollback`）は Liquibase の Gradle プラグインで実行し、compose の PostgreSQL を使い続ける。テストの JVM へ移すかは別に判断する。
- 変更のないモジュールのテストを飛ばす Spring Modulith の `spring-modulith-junit` は、この ADR の範囲外とし、別に判断する。
- CI の fork の数は、main で複数回の実行の中央値を測ってから別に判断する。イメージは Testcontainers が取得し、`actions/cache` に保存しない（保存と展開が取得より遅くなりやすいため）。

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
