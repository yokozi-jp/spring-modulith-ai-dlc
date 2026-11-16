---
type: Convention
title: バックエンドのテストの実行基盤
description: バックエンドのテストが使うPostgreSQL、Redis、WireMockをテストのJVMごとに起動する仕組み、Gradleのforkの並列度、テストの実行のしかた、依存を足すときの決まりを定める規約。テストを実行する、テストの依存や並列度を変える、テストの接続先を読むコードを書くときに読む。
tags: [convention, backend, testing, testcontainers, gradle]
---

# バックエンドのテストの実行基盤

テストのJVMごとに、TestcontainersでPostgreSQLとRedisを、プロセス内でWireMockを起動し、Gradleのforkを並列にする。
forkどうしはDBを共有しないため、テストはほかのforkのデータを気にしない。
テストクラスで独自にコンテナや`DataSource`を起動しない。
決定の理由は[ADR-084](../adr/ADR-084-start-test-dependencies-per-fork-with-testcontainers.md)を参照する。

## 起動する依存

[InfrastructureContainers](../../backend/src/test/java/com/example/demo/testkit/InfrastructureContainers.java)は、テストのJVMごとに次を1つずつ起動する。

| 依存           | 起動のしかた         | 設定の正本                                                                 |
| -------------- | -------------------- | -------------------------------------------------------------------------- |
| PostgreSQL     | Testcontainers       | `docker/compose-test.yml`の`postgres`のイメージとコマンド、`docker/initdb` |
| Redis          | Testcontainers       | `docker/compose-test.yml`の`redis`のイメージ                               |
| 決済代行の偽物 | プロセス内のWireMock | `docker/wiremock`のスタブ                                                  |

イメージとコマンドは`docker/compose-test.yml`から読むため、版を上げるときはcomposeのファイルだけを変える。
使い捨てのDBに限り、`fsync=off`を足す。

起動するのは、最初のSpringのテストのコンテキストを作るときである。
[InfrastructureContainersContextCustomizerFactory](../../backend/src/test/java/com/example/demo/testkit/InfrastructureContainersContextCustomizerFactory.java)を`META-INF/spring.factories`で全テストへ登録している。
Springを起動しないテストだけを実行するforkでは、コンテナを起動しない。
コンテナはJVMの終了でTestcontainersのRyukが片付ける。

## マイグレーション

テストのJVMは、起動したPostgreSQLへ、マイグレーションロールでLiquibaseのchangesetを適用する。
適用する範囲は、Gradleが渡す`gradle.properties`の`databaseSchemaTag`までである。
`-PtestAllChangesets`を付けると、作りかけを含む全changesetを適用する（`task test-dev`）。
Gradleを通さずにIDEから実行してタグが渡らないときも、全changesetを適用する。

rollbackの検証（`task be-verify-migrations`）はテストの外で、composeのPostgreSQLを使う（[DB操作コマンドのリファレンス](../database/commands.md)）。

## 接続先の渡し方

接続先は、`application.yaml`のプレースホルダの名前で、最も優先するproperty sourceとしてSpringのコンテキストへ足す。
渡す名前は`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`、`MIGRATION_DB_USERNAME`、`MIGRATION_DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`PAYMENT_GATEWAY_BASE_URL`である。
テストの中では、`.env.test`の同じ名前の値は使われない。
接続先以外の設定（タイムアウト、OIDC、OpenTelemetryなど）は、これまでどおり`.env.test`から読む。

テストで接続先が要るときは、`Environment`から`spring.datasource.url`などを読む。
`System.getenv`で読まない。

環境変数から緩い束縛で設定へ直接つながる名前（`PAYMENT_GATEWAY_BASE_URL`と`payment-gateway.base-url`）は、`application.yaml`のプレースホルダを通らない。
このような設定の接続先を足すときは、束縛先の名前でも渡す。

## 並列度

`maxParallelForks`は既定でコア数の半分にする。
`-PtestForks=<n>`で変える。

forkはGradleが別のJVMとして起動し、テストクラスを振り分ける。
同じforkの中では、テストクラスを順に実行する。
同じforkの中のテストクラスは、DB、Springのコンテキストのキャッシュ、固定の時計を共有するため、これまでどおり後始末の規約（[バックエンドのDBテスト](testing-database.md)）に従う。

WSLで実行するときは、`.wslconfig`の`processors`がforkに使えるコアの上限になる。

## テストの実行

```bash
task be-test                                              # 全テストとカバレッジの検証
cd backend && ./gradlew test --tests '*OrderApiTest'      # 一部だけ
cd backend && ./gradlew test -PtestForks=1                # forkを1つにする
```

`./gradlew test`はカバレッジの検証を実行しないため、一部のテストだけを実行しても失敗しない。
カバレッジの検証は`./gradlew check`か`task be-test`で実行する。
テストにはDockerが必要である。

## 依存を足すとき

テストに新しい外部の依存が要るときは、`InfrastructureContainers`にコンテナを足し、イメージを`docker/compose-test.yml`に置く。
テストクラスに`@Testcontainers`、`@Container`、`@ServiceConnection`を付けない。
テストクラスごとにコンテナを起動すると、Springのコンテキストのキャッシュが分かれ、起動の時間が増えるためである。
1つのテストクラスだけが使う偽物（`PaymentGatewayClientIntegrationTest`のWireMockなど）は、そのクラスで起動し、`@DynamicPropertySource`で接続先を上書きしてよい。
