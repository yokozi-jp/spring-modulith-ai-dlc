---
type: Convention
title: バックエンドのテスト種別
description: バックエンドで検証対象に合う最小のテスト種別を選ぶ基準を定め、テストの種類を決めるときに読む規約。
tags: [convention, backend, testing]
---

# バックエンドのテスト種別

検証対象を満たす最小のテスト種別を選び、`@SpringBootTest`を既定にしない。
テストフレームワークはJUnit Jupiterを使い、依存はテストのJVMごとに起動する（[バックエンドのテストの実行基盤](testing-infrastructure.md)）。

## 推奨するテスト

検証対象ごとに、次の表の上から順に、検証を満たす最初のテスト種別を選ぶ。
下の行ほど起動する範囲が広く、実行に時間がかかる。

| 検証対象                                                            | 推奨するテスト                                                                       | 起動する範囲                             |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------ | ---------------------------------------- |
| 純粋な業務ロジック                                                  | Springを起動しないJUnitテスト                                                        | なし                                     |
| 入力範囲の広い不変条件                                              | QuickTheoriesをJUnitテストメソッド内で使うプロパティベーステスト                     | なし                                     |
| パッケージ構成、依存方向、禁止APIなどの静的な規約                   | ArchUnit、Spring Modulith、PMD、Error Proneの検査                                    | なし                                     |
| 外部システムのClientのHTTPの変換                                    | JDKの`HttpServer`を使う、Springを起動しないJUnitテスト                               | なし                                     |
| 型とJSONシリアライズ                                                | `@JsonTest`などの狭いスライス                                                        | JSONの構成だけ                           |
| 通常のDBアクセス                                                    | `@DatabaseTest`                                                                      | jOOQとDB                                 |
| コミット時にだけ起きるDB挙動                                        | `@CommittedDatabaseTest`                                                             | アプリケーション全体とDB                 |
| モジュールの中の振る舞いとイベント                                  | `@ApplicationModuleTest`、`Scenario`、`PublishedEvents`、`AssertablePublishedEvents` | 対象のモジュールと、`mode`で選んだ依存先 |
| 外部システムのClientの試行回数、circuit breaker、イベント出版の状態 | WireMockを使う`@ApplicationModuleTest`                                               | 対象のモジュールと依存先                 |
| アプリケーション全体の起動と横断的な配線、HTTP APIの契約            | フルの`@SpringBootTest`                                                              | アプリケーション全体                     |
| 既存テストが業務コードの変化を検出できるか                          | PITミューテーションテスト                                                            | テストごとに異なる                       |

- モジュールの中で完結する振る舞いは、`@SpringBootTest`でなく`@ApplicationModuleTest`で検証する。
- `@ApplicationModuleTest`の`mode`は、検証に要る依存先を満たす最も狭いものを選ぶ。
  既定の`STANDALONE`は対象のモジュールだけを、`DIRECT_DEPENDENCIES`は直接の依存先を、`ALL_DEPENDENCIES`は推移的な依存先までを起動する。
- 外部システムのClientの検証の分け方は[外部システムのClient](class-roles/external-client.md)と[ADR-072](../adr/ADR-072-fake-external-systems-with-wiremock.md)に従う。
- PITは、PRでは必須でない`mutation-test.yml`が差分のクラスを変異させるので、生き残った変異をレポートで確かめる。

`task be-test`は、JaCoCoで全体のbranch 88%とline 95%、クラスごとのbranch 65%とline 70%を下回ると失敗する。
検証は`jacocoTestCoverageVerification`が行い、`./gradlew test`だけでは実行しない。
下限を満たすためだけのテストは書かず、テストで到達しない分岐は削除するか、振る舞いとして検証する。

プロパティベーステストとミューテーションテストの採用理由は[ADR-012](../adr/ADR-012-adopt-property-based-and-mutation-testing.md)を、PITの実行の契機とcoverageの下限は[ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)を参照する。
テストの依存の起動と並列度は[バックエンドのテストの実行基盤](testing-infrastructure.md)を参照する。
DBテストの隔離とトランザクションは[バックエンドのDBテスト](testing-database.md)を参照する。
テストコード、Springコンテキスト、失敗診断、非同期待機の書き方は[バックエンドのテストコードの書き方](testing-code-style.md)を参照する。
静的な検査の実装は[バックエンドのアーキテクチャテスト](architecture-tests.md)を参照する。
日時を検証するテストは[日時とタイムゾーンの規約](../datetime/timezone-conventions.md)に従う。
