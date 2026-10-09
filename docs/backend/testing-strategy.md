---
type: Convention
title: バックエンドのテスト種別
description: バックエンドで検証対象に合う最小のテスト種別を選ぶ基準を定め、テストの種類を決めるときに読む規約。
tags: [convention, backend, testing]
---

# バックエンドのテスト種別

検証対象を満たす最小のテスト種別を選び、`@SpringBootTest`を既定にしない。
テストフレームワークはJUnit Jupiterを使う。

## 選択基準

- 純粋な業務ロジックは、Springコンテキストを起動しないJUnitテストで検証する。
- 入力範囲の広い不変条件は、QuickTheoriesをJUnitテストメソッド内で使うプロパティベーステストで検証する。
- 型とJSONシリアライズだけを検証するときは、`@JsonTest`などの狭いスライスを使う。
- パッケージ構成、依存方向、禁止APIなどの静的な規約は、ArchUnit、Spring Modulith、PMD、Error Proneの検査を使う。
- 通常のDBアクセスは`@DatabaseTest`を使う。
- コミット時にだけ起きるDB挙動は`@CommittedDatabaseTest`を使う。
- モジュールとイベントの挙動は、`@ApplicationModuleTest`、`Scenario`、`PublishedEvents`または`AssertablePublishedEvents`を使う。
- アプリケーション全体の起動と横断的な配線は、フルの`@SpringBootTest`で検証する。
- 外部システムのClientのHTTPの変換は、JDKの`HttpServer`を使うSpringを起動しないJUnitテストで検証する。
  リトライ、circuit breaker、イベント出版の状態は、WireMockを使う`@ApplicationModuleTest`で検証する（[外部システムのClient](class-roles/external-client.md)、[ADR-072](../adr/ADR-072-fake-external-systems-with-wiremock.md)）。
- 既存テストが業務コードの変化を検出できるか確認するときは、PITミューテーションテストを使う。

プロパティベーステストとミューテーションテストの採用理由は[ADR-012](../adr/ADR-012-adopt-property-based-and-mutation-testing.md)を参照する。
DBテストの隔離とトランザクションは[バックエンドのDBテスト](testing-database.md)を参照する。
テストコード、Springコンテキスト、失敗診断、非同期待機の書き方は[バックエンドのテストコードの書き方](testing-code-style.md)を参照する。
静的な検査の実装は[バックエンドのアーキテクチャテスト](architecture-tests.md)を参照する。
日時を検証するテストは[日時とタイムゾーンの規約](../datetime/timezone-conventions.md)に従う。
