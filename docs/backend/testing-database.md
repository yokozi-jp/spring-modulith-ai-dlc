---
type: Convention
title: バックエンドのDBテスト
description: 実PostgreSQLを使うバックエンドテストの隔離環境、ロールバック、コミット後の後始末、イベント検証を定め、DBへ書き込むテストを作るときに読む規約。
tags: [convention, backend, testing, database, spring-modulith]
---

# バックエンドのDBテスト

DBへ書き込むテストは、隔離したテスト用スタックに対して`@DatabaseTest`または`@CommittedDatabaseTest`で実行する。
開発用DBと本番DBをテストに使わず、各テストへ手書きの後始末を追加しない。

## 実行環境

DBテストは、`docker/compose-test.yml`のPostgreSQL 5433とRedis 6380を`.env.test`で起動した隔離スタックに対して実行する。
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

## Spring Modulithのイベント

イベントの発行、購読、完了を検証するときは、`event_publication`へテストから行を追加しない。
`@ApplicationModuleTest`でモジュールを起動し、`Scenario`でイベントを発行し、`PublishedEvents`または`AssertablePublishedEvents`で結果を検証する。
型マッピングなどイベントと無関係な検証では、業務テーブルまたはテスト専用テーブルを使う。
