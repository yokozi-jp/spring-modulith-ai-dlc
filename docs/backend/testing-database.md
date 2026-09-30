---
type: Convention
title: バックエンドの DB テスト
description: DB に書き込むバックエンドテストの隔離方式（@DatabaseTest のロールバックと @CommittedDatabaseTest のコミットと自動 DELETE）、@Transactional を被せてよい条件、Spring Modulith のイベントテストの書き方を定める。
tags: [convention, backend, testing, database, spring-modulith]
---

# バックエンドの DB テスト

## 隔離と後始末

DBに書き込むテストは、次の二択で書く。
手書きの `try/finally` による後始末を新しく書かない。
各テストに散らした後始末は重複しやすく、書き忘れると他のテストに行が残るからである。

- 既定は `@DatabaseTest`（[DatabaseTest](../../backend/src/test/java/com/example/demo/testkit/DatabaseTest.java)）。jOOQ スライス（`@JooqTest`）と実 PostgreSQL で動き、各テスト後にロールバックするので、書いた行は自動で消える。
- コミットが必要なときだけ `@CommittedDatabaseTest`（[CommittedDatabaseTest](../../backend/src/test/java/com/example/demo/testkit/CommittedDatabaseTest.java)）。[CleanGeneratedTablesExtension](../../backend/src/test/java/com/example/demo/testkit/CleanGeneratedTablesExtension.java) が各テスト後に、jOOQ が生成したアプリケーションテーブルの行だけを `DELETE` する。Liquibase 管理テーブルは codegen で除外済みなので触らない。後始末は本番と同じ DML 限定のアプリロールで動くため、`TRUNCATE` ではなく `DELETE` を使う（アプリロールは `CREATE`/`ALTER`/`DROP`/`TRUNCATE` を持たない。[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)）。

どちらも移行済みのテストDBを前提にする。
ローカルでは `task test`（確定版）または `task test-dev`（作りかけ含む）が、DBの起動、マイグレーション、後片付けまで行う。

```java
// 良い例：ふつうの「書いて読む」テストはロールバックに任せる
@DatabaseTest
class SomethingRepositoryTest {

  /** 検証対象の jOOQ コンテキスト。 */
  @Autowired private DSLContext dslContext;

  @Test
  void insertsAndReadsBack() {
    // insert → select。後始末は書かない（自動ロールバック）。
  }
}
```

```java
// 悪い例：手書きの後始末を各テストに散らす
@SpringBootTest
class SomethingTest {
  @Test
  void roundTrip() {
    try {
      // insert / select
    } finally {
      // delete で後始末（重複しやすく、忘れやすい）
    }
  }
}
```

## @Transactional の可否

`@Transactional` は禁止ではない。
`@DatabaseTest`（`@JooqTest`）は既定でトランザクションを張ってロールバックする方式であり、ふつうのDBテストではこれでよい。

ただし、コミット時にしか起きない挙動を検証するテストに、ロールバックのトランザクションを被せない。
ロールバックは次を素通りさせ、通ったつもりの誤検知を生む。

- `@TransactionalEventListener(AFTER_COMMIT)`。Spring Modulith はイベントの発行と完了をコミット連動で行うため、ロールバックすると after-commit の処理が走らない。
- 遅延制約（`DEFERRABLE INITIALLY DEFERRED`）やコミット時に評価される制約トリガ。
- 別接続や別スレッドから見た、実際に永続化された状態（非同期処理を含む）。

これらが効くテストは `@CommittedDatabaseTest`（非トランザクション）を使う。
`@Transactional` はテスト中の処理を単一接続、単一トランザクションに固定するため、`REQUIRES_NEW` や新規接続を取るコードは本番と挙動が変わる点にも注意する。

## Spring Modulith のイベントテスト

- イベントの発行、購読、完了そのものを検証するときは、`event_publication` テーブルへ手で行を入れない。このテーブルは Spring Modulith が所有するインフラ用レジストリであり、手書きは内部スキーマへの結合を生む。
- 代わりに `@ApplicationModuleTest` でモジュールを起動し、`Scenario` でイベントを publish し、`PublishedEvents` / `AssertablePublishedEvents` で結果を検証する。
- 型マッピングなど、イベント挙動と無関係な検証で Modulith のテーブルを借用しない。業務ドメインのテーブル、またはテスト専用テーブルに対して検証する。
