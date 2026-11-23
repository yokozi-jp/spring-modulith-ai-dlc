---
type: Convention
title: バックエンドのテストの実行基盤
description: バックエンドのテストが使うPostgreSQL、WireMockをテストのJVMごとに起動する決まり、forkの並列度、テストの実行のしかた、依存を足すときの決まりを定める規約。テストを実行する、テストの依存や並列度を変える、テストで接続先を読むコードを書くときに読む。
tags: [convention, backend, testing, testcontainers, gradle]
---

# バックエンドのテストの実行基盤

テストのJVMごとに、PostgreSQL、WireMockのコンテナを1つずつ起動し、Gradleのforkを並列にする。
テストの依存のコンテナは[InfrastructureContainers](../../backend/src/test/java/com/example/demo/testkit/InfrastructureContainers.java)だけが起動する。
起動のしかたと接続先の渡し方はクラスのJavadocに、決定の理由は[ADR-085](../adr/ADR-085-start-test-dependencies-per-fork-with-testcontainers.md)にある。

## 依存の正本

コンテナのイメージとWireMockのコマンドは`docker/compose-test.yml`から読む。
版を上げるときは、composeのファイルだけを変える。
PostgreSQLの初期化は`docker/initdb`を、WireMockのスタブは`docker/wiremock/mappings`を使う。
コンテナのポートは`127.0.0.1`だけで公開する。

テストのJVMは、起動したPostgreSQLへ`gradle.properties`の`databaseSchemaTag`までのchangesetを適用する。
`-PtestAllChangesets`を付けると、作りかけを含む全changesetを適用する（`task test-dev`）。

## 設定と接続先

接続先（`DB_*`、`MIGRATION_DB_*`、`PAYMENT_GATEWAY_BASE_URL`）は、起動したコンテナの値がほかのすべての設定より優先される。
接続先以外の設定は`.env.test`から読む。
Gradleの`test`とPITは`.env.test`を`spring.config.import`で読み込み、開発用の`.env`を使わない。
`-PtestEnvFile=<backendからの相対パス>`で読むファイルを変える。

`.env.test`のOTLPの送信先（`OTEL_EXPORTER_OTLP_ENDPOINT`）にCollectorはない。
[OtlpLogExportContextCustomizerFactory](../../backend/src/test/java/com/example/demo/testkit/OtlpLogExportContextCustomizerFactory.java)が、すべてのSpringのテストのコンテキストで`management.logging.export.otlp.enabled=false`にし、OTLPのログのexporterを作らない。
GradleでもIDEでも同じに効く。
exporterを残すと、キャッシュしたコンテキストの背景のスレッドが送信の失敗をERRORで標準出力へ出し、`OutputCaptureExtension`で出力を調べるテストが不安定になる。
トレースとメトリクスのexporterは、Spring Bootのテストの既定で止まる。テストに`@AutoConfigureTracing`や`@AutoConfigureMetrics`を付けると、同じ背景の出力が戻る。
`OTEL_SDK_DISABLED=true`はSDKごと止め、LogRecordを観測する`ObservabilityContractTest`が使えなくなるので使わない。
exporterを作らないことは`OtlpLogExportContextCustomizerFactoryTest`が確かめる。

テストで接続先が要るときは、Springの`Environment`から`spring.datasource.url`などを読む。
`.env.test`やOSの環境変数から直接読まない。
接続先がコンテナを指すことは[InfrastructureContainersTest](../../backend/src/test/java/com/example/demo/testkit/InfrastructureContainersTest.java)が確かめる。

## 並列度

`maxParallelForks`は既定でコア数の半分にし、`-PtestForks=<n>`で変える。
forkどうしは別のコンテナを使う。
同じforkの中ではテストクラスを順に実行し、DB、Springのコンテキストのキャッシュ、固定の時計を共有する。
テストの書き方の決まりは[バックエンドのテストコードの書き方](testing-code-style.md)の「並列と実行順」に従う。

WSLで実行するときは、`.wslconfig`の`processors`がforkに使えるコアの上限になる。

`maxParallelForks`は、ホストの負荷もほかのセッションの数も見ない。
複数のセッションが同時にテストを流すと、forkの合計が`nproc`に達し、コンテナやGradleのプロセスと合わせてCPUを取り合う。
`-PtestForks=<n>`でforkを絞ると、その実行は終えられるが、測った所要時間は負荷が低いときと比べられない。
遅いときは、先に[バックエンドのテストが遅いときの調べ方](runbook-slow-tests.md)でload averageを見る。

## テストの実行

テストにはDockerと`.env.test`が必要である。
`.env.test`は、`task be-test`などのTaskが`.env.test.example`から作る。

```bash
task be-test                                              # 全テストとカバレッジの検証
cd backend && ./gradlew test --tests '*OrderApiTest'      # 一部だけ
cd backend && ./gradlew test -PtestForks=1                # forkを1つにする
```

`./gradlew test`はカバレッジを検証しない。
カバレッジの検証は`./gradlew check`か`task be-test`で実行する。

## 依存を足すとき

- テストに新しい外部の依存が要るときは、`InfrastructureContainers`にコンテナを足し、イメージを`docker/compose-test.yml`に置く。
- `testkit`の外のテストクラスは、Testcontainersの型（`@Testcontainers`、`@Container`、`@ServiceConnection`、`GenericContainer`など）を使わない。
  `TestConventionsArchTest`の`containersAreStartedOnlyByTestkit`が検査する。
- 1つのテストクラスだけが使う偽物（`PaymentGatewayClientIntegrationTest`のプロセス内のWireMockなど）は、そのクラスで起動し、`@DynamicPropertySource`で接続先を上書きしてよい。
