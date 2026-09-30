---
type: Convention
title: バックエンドのテスト方針と種類の選び方
description: バックエンド（Spring Boot 4 / Spring Modulith / jOOQ / PostgreSQL）のテストで、最小のスライスを選ぶ方針、対象ごとのテスト種類、コンテキストキャッシュ、失敗時の診断情報、非同期待機、実行コマンドを定める。
tags: [convention, backend, testing]
---

# バックエンドのテスト方針と種類の選び方

## 全体方針

- テストフレームワークは JUnit 5（Jupiter）を使う。
- 最小のスライスを選ぶ。必要な範囲だけを起動するテストほど速く、壊れにくい。フルの `@SpringBootTest` を既定にしない。
- テストは UTC の JVM で一度だけ走らせる（`build.gradle` の `test` が `-Duser.timezone=UTC` を渡す）。ローカルタイムゾーンに依存する検証を書かない。日時の検証は [日時とタイムゾーンの規約](../datetime/timezone-conventions.md) の「テスト」に従う。
- DBテストは使い捨てスタック（`docker/compose-test.yml` の PostgreSQL 5433 / Redis 6380、`.env.test`）に対して実行する。開発用スタック（5432/6379）や本番DBには向けない。
- 静的解析（PMD、SpotBugs、Spotless）はテストコードにも適用される。書いたら `task be-lint` で確認する。

## テストの選び方

扱う対象に応じて、次の順で最小のものを選ぶ。

- **型とシリアライズだけ**：`@JsonTest` などの狭いスライス。DB も Web も起動しない（例：[JacksonConfigTest](../../backend/src/test/java/com/example/demo/config/JacksonConfigTest.java)）。
- **静的な構造と規約**：ArchUnit テスト。Spring コンテキストを起動しない。
- **ふつうのDBアクセス（書いて読む）**：`@DatabaseTest`。詳細は [バックエンドの DB テスト](testing-database.md) を参照する。
- **コミット時挙動が効くDBテスト**：`@CommittedDatabaseTest`。詳細は同上。
- **モジュールとイベントの挙動**：Spring Modulith の `@ApplicationModuleTest` と `Scenario`、`PublishedEvents`（`AssertablePublishedEvents`）を使う。
- **横断的な起動確認と配線**：フルの `@SpringBootTest`。共有構成を使ってコンテキストを1つに揃える（後述）。

## コンテキストキャッシュ

Spring はテスト構成（アノテーションと `@Import` の組）ごとにコンテキストをキャッシュして再利用する。
本数が増えてもコンテキストの再ロードを増やさないため、次を守る。

- フルの `@SpringBootTest` は、共有の `@TestConfiguration`（[SharedTestConfiguration](../../backend/src/test/java/com/example/demo/testkit/SharedTestConfiguration.java)）を `@Import` して構成を揃える。テストごとに個別の `@Import` や `@MockBean` を足して構成をばらけさせない。
- OIDC クライアント登録など、複数のテストで共通して要る差し替えは共有構成に集約する。

## 失敗時の診断情報

失敗ログだけで失敗対象と再現条件を特定できるようにする。
ログには、該当する範囲で対象、入力、expected と actual、再現情報を残す。
対象にはレコードID、ファイルパス、リクエスト、イベントキーなどが該当し、再現情報には乱数seed、期限、最後に観測した状態などが該当する。

- JUnit の等価比較では expected と actual の引数を維持し、メッセージには対象と入力を補う。
- AssertJ は標準の差分を維持できる `as(...)` を使って対象と入力を補う。`withFailMessage(...)` は標準の差分を上書きするため、置き換える理由がある場合に限る。
- テスト内で `Optional` が空なら、引数なしの `orElseThrow()` を使わず、対象と検索条件を持つ `AssertionError` などを送出する。
- プロパティベーステストでは、縮小された反例と再現に必要なseedを失敗ログから取得できるようにする。
- 修正方法をメッセージへ書くのは、アーキテクチャ規則や禁止APIのように修正方針が一つに決まる場合に限る。振る舞いテストでは実装方法を固定せず、契約と診断情報を示す。

```java
assertEquals(
    expected,
    actual,
    () -> "publicationId=" + publicationId + ", column=publication_date");
```

```java
assertThat(actual)
    .as("publicationId=%s の保存往復", publicationId)
    .isEqualTo(expected);
```

```java
final Instant actual =
    result.orElseThrow(
        () ->
            new AssertionError(
                "event_publication が見つからない: publicationId=" + publicationId));
```

## 非同期待機

固定時間を待つ `Thread.sleep()` は、処理が早く終わっても待機し、遅い環境では不足してテストを不安定にするため使わない。
イベントの発行と購読は Spring Modulith の `Scenario` で条件を待つ。
それ以外の非同期処理では、対象APIが提供する期限付きの条件待機か、JUnit の非プリエンプティブな `assertTimeout` を使う。
タイムアウト時には対象ID、期待条件、期限、最後に観測した状態を出す。

`assertTimeoutPreemptively` は別スレッドで処理を実行するため、トランザクション、セキュリティコンテキスト、ログのコンテキストが本番経路と変わり得る。
このため既定にはしない。

## 実行

- ローカルの総合確認（確定版）：`task test`。使い捨てスタックを起動し、マイグレーション検証とテストを通してから後片付けする。
- 作りかけ changeset を含めて回す：`task test-dev`。全 changeset を適用してからテストする。
- マイグレーションの rollback 検証だけ：`task be-verify-migrations`。
- 依存が起動済みの環境（CI 部品）：`task be-test` / `task be-test-dev`。
- Gradle は失敗したテストの例外、cause、スタックトレースを省略せずターミナルへ出す。
- CI は失敗時に JUnit XML と HTML レポートを `backend-test-results` artifact として保存する。
