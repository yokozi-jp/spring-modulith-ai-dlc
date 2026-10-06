---
type: Convention
title: バックエンドのDBテスト
description: 実PostgreSQLを使うバックエンドテストの隔離環境、ロールバック、コミット後の後始末、テスト専用テーブル、イベント検証を定め、DBへ書き込むテストを作るとき、業務テーブルのない共通処理を実DBで確かめるときに読む規約。
tags: [convention, backend, testing, database, spring-modulith]
---

# バックエンドのDBテスト

DBへ書き込むテストは、隔離したテスト用スタックに対して`@DatabaseTest`または`@CommittedDatabaseTest`で実行する。
開発用DBと本番DBをテストに使わず、各テストへ手書きの後始末を追加しない。

## 実行環境

DBテストは、`docker/compose-test.yml`のPostgreSQL 5433とRedis 6380を`.env.test`で起動した隔離スタックに対して実行する。
`.env.test`は、Gitで管理する`.env.test.example`からコピーして作るローカルファイルで、`task test`が無ければ作る。
`task test`は確定済みchangesetを、`task test-dev`は作りかけを含む全changesetを適用し、テスト後にボリュームを削除する。
どちらのテストアノテーションも、マイグレーション済みのテストDBを前提にする。

実行テストに`@JooqTest`を直接付けず、`@DatabaseTest`か`@CommittedDatabaseTest`を使う。
テスト用DBへの接続、ロールバック、後始末の構成を二つの合成アノテーションにまとめ、テストごとに構成が分かれないようにするためである。

## ロールバックするテスト

通常の書き込みと読み取りには、[DatabaseTest](../../backend/src/test/java/com/example/demo/testkit/DatabaseTest.java)を使う。
`@DatabaseTest`は`@JooqTest`と実PostgreSQLを使い、各テスト後にトランザクションをロールバックする。
通常のDBテストへ`@Transactional`を追加せず、`@DatabaseTest`のトランザクションに任せる。

## コミットするテスト

コミット時にだけ起きる挙動には、[CommittedDatabaseTest](../../backend/src/test/java/com/example/demo/testkit/CommittedDatabaseTest.java)を使う。
[CleanGeneratedTablesExtension](../../backend/src/test/java/com/example/demo/testkit/CleanGeneratedTablesExtension.java)は、各テスト後にjOOQ生成対象のアプリケーションテーブルを`DELETE`する。
Liquibase管理テーブルはコード生成から除外されているため、後始末の対象にしない。
後始末は本番と同じDML限定のアプリロールで実行し、`TRUNCATE`を使わない。
DBロールの決定は[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)を参照する。

次の挙動を検証するときは、テストへロールバック用トランザクションを被せない。

- `@TransactionalEventListener(AFTER_COMMIT)`とSpring Modulithのイベント発行完了。
- 遅延制約とコミット時に評価する制約トリガ。
- 別接続または別スレッドから見た永続化済み状態。
- `REQUIRES_NEW`または新しい接続を取得する処理。

## テスト専用テーブル

業務テーブルがまだないため、`shared`の共通処理（`TableWriter`など）を実PostgreSQLで確かめるテストは、[FixtureTablesExtension](../../backend/src/test/java/com/example/demo/testkit/FixtureTablesExtension.java)を`@ExtendWith`で付ける。
この拡張は、テストクラスの前に`fixture`スキーマとテーブルを作り、後で消す。
DDLは本番と同じくマイグレーションロール（`.env.test`の`MIGRATION_DB_*`）で実行し、アプリロールにはDMLだけを付与する。
テストクラスは順に実行されるため、スキーマ検査はこのスキーマを見ない。
使ってよいのは`shared`の共通処理のテストだけであり、業務テーブルができたらそのテーブルで確かめる。

二つのセッションをまたぐテスト（古い保存の競合、`lock_timeout`）は、`@DatabaseTest`のトランザクションをAとし、`DataSource`から取った二つ目の接続をBにする。
Bの行はコミットするため、`@AfterTransaction`で自動コミットの接続から消す。
Aは待った後の再評価で行ロックを取り、ロールバックまで持つため、`@AfterEach`で消すとロック待ちになる。
接続はテストの接続poolの上限（`DB_POOL_MAXIMUM_SIZE=4`）に収める。

## Spring Modulithのイベント

イベントの発行、購読、完了を検証するときは、`event_publication`へテストから行を追加しない。
`@ApplicationModuleTest`でモジュールを起動し、`Scenario`でイベントを発行し、`PublishedEvents`または`AssertablePublishedEvents`で結果を検証する。
型マッピングなどイベントと無関係な検証では、業務テーブルまたはテスト専用テーブルを使う。
