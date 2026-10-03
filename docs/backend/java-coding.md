---
type: Convention
title: バックエンドの Java 実装規約
description: 手書きのバックエンドコードで使う Lombok、record、Domain Model の状態変更、ロギングの規約を定める。データキャリアやログ出力を書くとき、PMD や ArchUnit のロギング規則で失敗したときに読む。
tags: [convention, backend, java, logging]
---

# バックエンドの Java 実装規約

Lombok の `@Data` と `@Setter` を使わず、データキャリアは record にする。
Domain Model の状態は業務上の操作を表すメソッドで変更する。
ロギングは SLF4J facade と Lombok の `@Slf4j` に統一する。
これらの規則の検査方法は [バックエンドのアーキテクチャテスト](architecture-tests.md) に示す。

## 規約

手書きのバックエンドコードでは、Lombok の `@Data` と `@Setter` を使用しない。

次のデータキャリアには record を使う。

- モジュールルートの参照の結果、検索条件、イベント。
- 値オブジェクト。固定の値の集合は enum にする。
- Application の `<UseCase>Command` と `<UseCase>Result`。
- Presentation の `<UseCase>Request` と `<QueryResult>Response`。

Domain Model の状態は setter で公開せず、業務上の操作と不変条件を表すメソッドを介して変更する。

機能コードのロギングには SLF4J facade を使い、Logback、Log4j、Apache Commons Logging の実装 API を直接参照しない。

ログを出力する手書きクラスには Lombok の `@Slf4j` を付け、Logger フィールドと `LoggerFactory` を直接記述しない。

`@Slf4j` の使用規約はソース上の Logger 型と `LoggerFactory` 参照を PMD で禁止して強制する。

ベースパッケージ直下のロギング基盤設定は実装 API との接続を担うため、ArchUnit の facade 制約だけは対象外とする。
