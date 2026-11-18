---
type: Convention
title: バックエンドのDBテスト
description: 実PostgreSQLを使うバックエンドテストの隔離環境、ロールバック、コミット後の後始末、テスト専用テーブル、イベント検証を定め、DBへ書き込むテストを作るとき、業務テーブルのない共通処理を実DBで確かめるときに読む規約。
tags: [convention, backend, testing, database, spring-modulith]
---

# バックエンドのDBテスト

DBへ書き込むテストは、テストのJVMごとに起動するPostgreSQLに対して`@DatabaseTest`または`@CommittedDatabaseTest`で実行する。
開発用DBと本番DBをテストに使わず、各テストへ手書きの後始末を追加しない。

## 実行環境

DBテストは、テストのJVMごとにTestcontainersが起動し、changesetを適用したPostgreSQLに対して実行する（[バックエンドのテストの実行基盤](testing-infrastructure.md)）。
Gradleのforkごとに別のDBになるため、forkどうしの後始末は衝突しない。
`task be-test`は確定済みchangesetを、`task test-dev`は作りかけを含む全changesetを適用する。

実行テストに`@JooqTest`を直接付けず、`@DatabaseTest`か`@CommittedDatabaseTest`を使う。
テスト用DBへの接続、ロールバック、後始末の構成を二つの合成アノテーションにまとめ、テストごとに構成が分かれないようにするためである。

## ロールバックするテスト

通常の書き込みと読み取りには、[DatabaseTest](../../backend/src/test/java/com/example/demo/testkit/DatabaseTest.java)を使う。
`@DatabaseTest`は`@JooqTest`と実PostgreSQLを使い、各テスト後にトランザクションをロールバックする。
通常のDBテストへ`@Transactional`を追加せず、`@DatabaseTest`のトランザクションに任せる。

## モジュールのロールで読み書きするテスト

アプリロールは業務のスキーマに権限を持たず、トランザクションの名前（クラス名とメソッド名）から引いたモジュールのロールへ切り替えたときだけ、そのモジュールのスキーマを読み書きできる（[DB接続情報とロール分離](../database/connections.md)の「モジュールのロール」）。
テストのトランザクションも同じ規則で動く。

- **テストクラスがモジュールのパッケージにあるとき**：`@DatabaseTest`のロールバック用トランザクションは、テストクラスの名前（`com.example.demo.ordering...`）から、そのモジュールのロールで動く。`@JooqTest`は`@Configuration`を読み込まないため、`@DatabaseTest`が切り替えを行うトランザクションマネージャーを`@Import`する。
- **テストクラスがモジュールの外にあるとき、または別のモジュールの表を読み書きするとき**：[AsModule](../../backend/src/test/java/com/example/demo/testkit/AsModule.java)で、本番と同じ`set_config('role', ?, true)`を使い、モジュールのロールで本体を実行する。商品を登録する`TestProducts`のような補助は、`AsModule`の中で書く。
- **`Scenario#stimulate`の中でCommandHandlerを呼ぶとき**：`stimulate`は名前のないトランザクションで呼ぶため、そのままではアプリロールで動き、権限エラーになる。本番のControllerが名前付きのトランザクションで入るのと同じく、`AsModule`の中で呼ぶ。

業務のスキーマの読み書きを、モジュールの境界の外から確かめるテストは`ModuleRoleIsolationTest`、トランザクションマネージャーの切り替えとプールの接続への残留は`ModuleRoleTransactionTest`が確かめる。

次の経路は、トランザクションマネージャーを通るかを実装で確かめ、結果は次のとおりである。

- **`@DatabaseTest`（`@JooqTest`）のテストトランザクション**：名前はテストクラスの名前である。モジュールのパッケージのテストならモジュールのロール、それ以外はアプリロールで動く。
- **名前を付けない`TransactionTemplate`**：名前がなく、アプリロールで動く。
- **名前を付けた`TransactionTemplate`**：名前がモジュールのパッケージならモジュールのロールで動く。
- **`dsl.transaction`と`dsl.transactionResult`**：呼び出し元のトランザクションに参加するため、呼び出し元と同じロールで動く。呼び出し元がなければアプリロールで動く。
- **ShedLockとSpring Modulithの内部**：モジュールの名前を持たず、アプリロールで動く。`modulith`の表だけを使うため、権限エラーにならない。実物の`JooqLockProvider`がアプリロールでロックを取れることは、`ShedLockSessionTimeZoneTest`が確かめる。

## コミットするテスト

コミット時にだけ起きる挙動には、[CommittedDatabaseTest](../../backend/src/test/java/com/example/demo/testkit/CommittedDatabaseTest.java)を使う。
[CleanGeneratedTablesExtension](../../backend/src/test/java/com/example/demo/testkit/CleanGeneratedTablesExtension.java)は、各テスト後にjOOQ生成対象のアプリケーションテーブルの行を消す。
Liquibase管理テーブルはコード生成から除外されているため、後始末の対象にしない。
業務のスキーマの表は、アプリロールにもモジュールのロールにも別のモジュールの表を消す権限がないため、表の所有者（マイグレーションロール）の接続で`TRUNCATE`する。`TRUNCATE`は行レベルセキュリティの対象外で、モジュールとテナントの境界に左右されない。
`modulith`スキーマの表は、アプリロールで`DELETE`する。
消す前に、状態が`PUBLISHED`、`PROCESSING`、`RESUBMITTED`のイベント出版がなくなるまで最大10秒待つ。
非同期のListenerはテストの本体の後にコミットすることがあり、待たずに消すと、後から書いた行が次のテストに残るためである。
10秒待っても残るときは、消してからテストを失敗させる。
終わらない行を残すと、後のすべてのテストが上限まで待ち、後始末も止まるためである。
消すことも失敗したときは、原因が見えるよう、待ちの失敗に消すときの例外を`addSuppressed`で添える。
ShedLockのロックの行（`modulith.shedlock`）は消さず、各テスト後に全行の`lock_until`を`1970-01-01T00:00:00Z`（`Instant.EPOCH`）にして期限切れにする。
行を消すとShedLockが同じcontextで行を作り直さずロックを取れなくなり、期限を残すと`lock-at-least-for`の間、後に動く別のテストクラスがロックを待つためである。
DBロールの決定は[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)と[ADR-094](../adr/ADR-094-switch-to-module-database-roles.md)を参照する。

次の挙動を検証するときは、テストへロールバック用トランザクションを被せない。

- `@TransactionalEventListener(AFTER_COMMIT)`とSpring Modulithのイベント発行完了。
- 遅延制約とコミット時に評価する制約トリガ。
- 別接続または別スレッドから見た永続化済み状態。
- `REQUIRES_NEW`または新しい接続を取得する処理。

## テスト専用テーブル

業務テーブルがまだないため、`shared`の共通処理（`TableWriter`など）を実PostgreSQLで確かめるテストは、[FixtureTablesExtension](../../backend/src/test/java/com/example/demo/testkit/FixtureTablesExtension.java)を`@ExtendWith`で付ける。
この拡張は、テストクラスの前に`fixture`スキーマとテーブルを作り、後で消す。
DDLは本番と同じくマイグレーションロール（`InfrastructureContainers`が渡す`MIGRATION_DB_*`）で実行し、アプリロールにはDMLだけを付与する。`fixture`スキーマは業務のスキーマでないため、モジュールのロールに切り替えず、アプリロールのまま読み書きする。
同じforkの中ではテストクラスを順に実行し、forkごとにDBが分かれるため、スキーマ検査はこのスキーマを見ない（[バックエンドのテストの実行基盤](testing-infrastructure.md)の「並列度」）。
使ってよいのは`shared`の共通処理のテストだけであり、業務テーブルができたらそのテーブルで確かめる。
Springの外で共通カラムを登録するテストは、[TestCommonColumns](../../backend/src/test/java/com/example/demo/shared/infrastructure/persistence/TestCommonColumns.java)の`at`で共通処理を作り、`runAs`の中で登録する。

二つのセッションをまたぐテスト（古い保存の競合、`lock_timeout`）は、`@DatabaseTest`のトランザクションをAとし、`DataSource`から取った二つ目の接続をBにする。
Bの行はコミットするため、`@AfterTransaction`で自動コミットの接続から消す。
Aは待った後の再評価で行ロックを取り、ロールバックまで持つため、`@AfterEach`で消すとロック待ちになる。
接続はテストの接続poolの上限（`DB_POOL_MAXIMUM_SIZE=4`）に収める。

## Spring Modulithのイベント

イベントの発行、購読、完了を検証するときは、`event_publication`へテストから行を追加しない。
`@ApplicationModuleTest`でモジュールを起動し、`Scenario`でイベントを発行し、`PublishedEvents`または`AssertablePublishedEvents`で結果を検証する。
型マッピングなどイベントと無関係な検証では、業務テーブルまたはテスト専用テーブルを使う。
