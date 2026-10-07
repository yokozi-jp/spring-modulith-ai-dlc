---
type: Convention
title: jOOQコード生成物の管理
description: jOOQコードを生成する時期、生成先、生成物のパッケージとSpring Modulithのモジュール、changesetと生成物を同じ変更で管理する規約を定め、生成コードを更新するときに読む。
tags: [convention, database, jooq, generated-code]
---

# jOOQコード生成物の管理

changesetを追加した変更ではjOOQコードを再生成し、changesetと生成物を同じ変更として管理する。
通常のJavaコンパイル、アプリケーションのビルド、本番デプロイではコード生成を実行しない。

## 生成する時期

作りかけのchangesetから生成するときは、`task be-migrate-dev`の後に`task be-generate-jooq`を実行する。
スキーマタグを確定した後は、`task be-refresh-jooq`でマイグレーションと再生成を続けて実行する。
現在のDBを変更せず再生成だけを行う場合は、`task be-generate-jooq`を使う。
各コマンドのDBへの影響範囲は[DB操作コマンドのリファレンス](commands.md)を参照する。

本番のDBマイグレーションとアプリケーションデプロイでは、`jooqCodegen`と`migrateAndGenerateJooq`を実行しない。
稼働DBの資格情報を開発端末へ配布してコード生成しない。

## 生成物の管理

生成先は`backend/src/generated/jooq`とする。
生成コードは対応するLiquibase changesetと同じ変更へ含める。
`compileJava`と`bootJar`は、リポジトリで管理する生成コードをコンパイルし、DBへ接続して再生成しない。
生成コードは手書きコード向けの静的解析とフォーマットから除外する。
生成先のパッケージ`com.example.demo.jooq`には生成コードだけを置き、手書きのクラスを置かない（[バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)の「解析対象と実行」）。

## 生成物のパッケージとモジュール

`com.example.demo.jooq`はベースパッケージの直下にあるため、Spring Modulithの`jooq`モジュールになり、サブパッケージの生成型はその内部の型になる。
`jooqCodegen`は生成のたびに、全生成型を公開するOPENの`package-info.java`を`com.example.demo.jooq`に書く。
この`package-info.java`は手で書き換えない（[ADR-067](../adr/ADR-067-open-jooq-generated-module.md)）。

生成型のパッケージは、生成の対象のスキーマの数で変わる。

- **スキーマが1つ**：今のmainの`modulith`だけの状態。`Tables`、`Keys`、`Indexes`、スキーマの定義はルートの`com.example.demo.jooq`に、テーブルは`tables`に、Recordは`tables.records`に生成される。
- **スキーマが2つ以上**：スキーマの定義、`Tables`、`Keys`、`Indexes`、テーブル、Recordが`com.example.demo.jooq.<スキーマ名>`のサブパッケージに移る。ルートの`Tables`はなくなり、`DefaultCatalog`だけが残る。

2つ目のスキーマを足す変更では、既存の生成型のimport（`com.example.demo.jooq.tables.EventPublication`を使う`JooqGeneratedMappingTest`と`JooqInstantRoundTripTest`）を`com.example.demo.jooq.modulith.tables`に直し、同じ変更に含める。
テスト用の手書きのテーブル（`com.example.demo.jooq.tables.Fixture*Table`）は生成物ではないため移さない。

jOOQ採用と生成環境の決定は[ADR-003](../adr/ADR-003-adopt-jooq-for-data-access.md)を参照する。
生成物をGit管理する理由は[ADR-004](../adr/ADR-004-commit-jooq-generated-code.md)を参照する。
日時型のマッピングは[日時とタイムゾーンの規約](../datetime/timezone-conventions.md)を参照する。
