---
inclusion: fileMatch
fileMatchPattern: ["backend/src/test/**/*.java", "backend/src/test/**/*.gradle"]
name: backend-testing-conventions
description: バックエンド（Spring Boot 4 / Spring Modulith / jOOQ / PostgreSQL）のテストコード規約。テストスライスと @SpringBootTest の使い分け、DBテストの隔離方式（@DatabaseTest のロールバックと @CommittedDatabaseTest のコミット＋自動 TRUNCATE）、@Transactional の可否と理由、Spring Modulith のイベントテスト作法、日時の固定と検証、コンテキストキャッシュ、静的解析（PMD CommentRequired・Spotless）に沿ったコード規約、実行コマンドを定める。backend のテストを書く・直すときに使用する。
---

# バックエンドのテストコード規約

`backend/src/test/` 配下の Java テストを書く・直すときは、以下に従う。
対象は Spring Boot 4 系、Spring Modulith、jOOQ、PostgreSQL のスタックである。

基準となる実装は次を参照する。

- ロールバック隔離のスライス：#[[file:backend/src/test/java/com/example/demo/support/DatabaseTest.java]]
- コミット＋自動後始末：#[[file:backend/src/test/java/com/example/demo/support/CommittedDatabaseTest.java]]
- 後始末拡張：#[[file:backend/src/test/java/com/example/demo/support/TruncateGeneratedTablesExtension.java]]
- 共有テスト構成：#[[file:backend/src/test/java/com/example/demo/support/SharedTestConfiguration.java]]
- ArchUnit の解析対象限定：#[[file:backend/src/test/java/com/example/demo/architecture/ProductionCodeOnly.java]]
- 静的解析ルール：#[[file:backend/config/pmd/ruleset.xml]]
- テスト用環境変数：#[[file:.env.test]]
- 日時の規約：#[[file:.kiro/steering/datetime-timezone-conventions.md]]

## 全体方針

- テストフレームワークは JUnit 5（Jupiter）を使う。
- **最小のスライスを選ぶ**。必要な範囲だけを起動するテストほど速く、壊れにくい。フルの `@SpringBootTest` を既定にしない。
- テストは UTC の JVM で一度だけ走らせる（`build.gradle` の `test` が `-Duser.timezone=UTC` を渡す）。ローカルタイムゾーンに依存する検証を書かない。
- DBテストは使い捨てスタック（`docker/compose-test.yml` の PostgreSQL 5433 / Redis 6380、`.env.test`）に対して実行する。開発用スタック（5432/6379）や本番DBには向けない。
- 静的解析（PMD・SpotBugs・Spotless）はテストコードにも適用される。書いたら `make be-lint` で確認する。

## テストの選び方

扱う対象に応じて、次の順で最小のものを選ぶ。

- **型・シリアライズだけ**：`@JsonTest` などの狭いスライス。DB も Web も起動しない（例：#[[file:backend/src/test/java/com/example/demo/config/JacksonConfigTest.java]]）。
- **静的な構造・規約**：ArchUnit テスト。Spring コンテキストを起動しない。
- **ふつうのDBアクセス（書いて読む）**：`@DatabaseTest`。jOOQ スライス（`@JooqTest`）＋実 PostgreSQL で、各テスト後に自動ロールバックする。後始末は不要。
- **コミット時挙動が効くDBテスト**：`@CommittedDatabaseTest`。実際にコミットし、後始末は拡張が自動で行う。
- **モジュール・イベントの挙動**：Spring Modulith の `@ApplicationModuleTest` と `Scenario` / `PublishedEvents`（`AssertablePublishedEvents`）を使う。
- **横断的な起動確認・配線**：フルの `@SpringBootTest`。共有構成を使ってコンテキストを1つに揃える（後述）。

`@DatabaseTest` と `@CommittedDatabaseTest` は移行済みのテストDBを前提にする。
ローカルでは `make test`（確定版）または `make test-dev`（作りかけ含む）が、DBの起動・マイグレーション・後片付けまで面倒を見る。

## DBテストの隔離と後始末

DBに書き込むテストは、次の二択で書く。**手書きの `try/finally` による後始末を新しく書かない**。

- 既定は `@DatabaseTest`。`@JooqTest` が各テスト後にロールバックするので、書いた行は自動で消える。
- コミットが必要なときだけ `@CommittedDatabaseTest`。`TruncateGeneratedTablesExtension` が各テスト後に、jOOQ が生成したアプリケーションテーブルだけを `TRUNCATE` する。Liquibase 管理テーブルは codegen で除外済みなので触らない。

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

ただし**コミット時にしか起きない挙動を検証するテストに、ロールバックのトランザクションを被せない**。
ロールバックは次を素通りさせ、通ったつもりの誤検知を生む。

- `@TransactionalEventListener(AFTER_COMMIT)`。Spring Modulith はイベントの発行・完了をコミット連動で行うため、ロールバックすると after-commit の処理が走らない。
- 遅延制約（`DEFERRABLE INITIALLY DEFERRED`）やコミット時に評価される制約トリガ。
- 別接続・別スレッドから見た、実際に永続化された状態（非同期処理を含む）。

これらが効くテストは `@CommittedDatabaseTest`（非トランザクション）を使う。
`@Transactional` はテスト中の処理を単一接続・単一トランザクションに固定するため、`REQUIRES_NEW` や新規接続を取るコードは本番と挙動が変わる点にも注意する。

## Spring Modulith のイベントテスト

- イベントの発行・購読・完了そのものを検証するときは、`event_publication` テーブルへ手で行を入れない。
このテーブルは Spring Modulith が所有するインフラ用レジストリであり、手書きは内部スキーマへの結合を生む。
- 代わりに `@ApplicationModuleTest` でモジュールを起動し、`Scenario` でイベントを publish し、`PublishedEvents` / `AssertablePublishedEvents` で結果を検証する。
- 型マッピングなど、イベント挙動と無関係な検証で Modulith のテーブルを借用しない。業務ドメインのテーブル、またはテスト専用テーブルに対して検証する。

## 日時の検証

日時の詳細は日時規約に従う（#[[file:.kiro/steering/datetime-timezone-conventions.md]]）。テスト側で特に守る点は次のとおり。

- 現在時刻を使うクラスには `Clock.fixed(...)` を渡す。引数なしの `now()` に依存しない。
- JSON の絶対時刻は `Z` 付き文字列との完全一致で検証する。
- テストデータの `Instant` は、DBとAPIが保持する精度（PostgreSQL はマイクロ秒）に合わせ、ナノ秒に依存させない。ナノ秒を含めると保存往復や文字列一致が丸めで失敗する。
- DB統合テストでは `SHOW TIME ZONE` が `UTC` であることと、`timestamptz` の保存往復を検証する。

## コンテキストキャッシュ

Spring はテスト構成（アノテーションと `@Import` の組）ごとにコンテキストをキャッシュして再利用する。
本数が増えてもコンテキストの再ロードを増やさないため、次を守る。

- フルの `@SpringBootTest` は、共有の `@TestConfiguration`（#[[file:backend/src/test/java/com/example/demo/support/SharedTestConfiguration.java]]）を `@Import` して構成を揃える。テストごとに個別の `@Import` や `@MockBean` を足して構成をばらけさせない。
- OIDC クライアント登録など、複数のテストで共通して要る差し替えは共有構成に集約する。

## コード規約

静的解析（#[[file:backend/config/pmd/ruleset.xml]]、SpotBugs、Spotless の Google Java Format）に通る形で書く。

- フォーマットは Google Java Format に従う。`make be-format` で整形し、`make be-lint` で確認する。
- **クラス・フィールドには Javadoc を付ける**（PMD `CommentRequired`）。`@Test` メソッドはパッケージプライベートにするので Javadoc は不要。
- テストクラス・テストメソッド・ネスト型はパッケージプライベートにする（`public` を付けない。JUnit 5 は package-private を実行する）。既存コードは意図を示すため `/* package */` の目印を添えている。
- アサーションには失敗時メッセージを添える。原因が一目で分かるようにする。
- アサーションは JUnit の `Assertions` と AssertJ のどちらでもよいが、1つのテストクラス内では揃える。
- テストクラス名は `...Test` を接尾辞にする。合成アノテーションや拡張などテストでない補助クラスには付けない。
- 複数アサーションは許容される（PMD `UnitTestContainsTooManyAsserts` は無効化済み）。1テストで1つの振る舞いを検証する範囲にとどめる。

## ArchUnit

- ArchUnit は手書きのプロダクションコードだけを解析する。生成コード（`jooq` / `generated`）とテストコードは `ProductionCodeOnly` で除外する（#[[file:backend/src/test/java/com/example/demo/architecture/ProductionCodeOnly.java]]）。
- `@ArchTest` フィールドはルールの説明として命名するため、定数命名規則（UPPER_SNAKE）とは別扱いにしている（PMD `FieldNamingConventions` は無効化済み）。

## 実行

- ローカルの総合確認（確定版）：`make test`。使い捨てスタックを起動し、マイグレーション検証とテストを通してから後片付けする。
- 作りかけ changeset を含めて回す：`make test-dev`。全 changeset を適用してからテストする。
- マイグレーションの rollback 検証だけ：`make be-verify-migrations`。
- 依存が起動済みの環境（CI 部品）：`make be-test` / `make be-test-dev`。

## 避けるべきアンチパターン

- 何でも `@SpringBootTest` で書く。起動が遅く、構成がばらけてコンテキストキャッシュが効かない。
- コミット時挙動を検証するテストに `@Transactional` のロールバックを被せる。after-commit や制約が素通りし、誤検知になる。
- 各DBテストに手書きの `try/finally` 後始末を書く。`@DatabaseTest`（ロールバック）か `@CommittedDatabaseTest`（自動 `TRUNCATE`）に任せる。
- Spring Modulith の `event_publication` へ手で行を入れて、イベント挙動やイベントと無関係な検証を行う。
- ローカルタイムゾーンや引数なし `now()`、ナノ秒精度に依存する。
- クラス・フィールドの Javadoc を省く（PMD で失敗する）。
