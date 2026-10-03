---
type: Architecture
title: バックエンドアーキテクチャ
description: Package by feature とオニオンアーキテクチャによるバックエンドの構造と依存方向を説明する。パッケージ構成、モジュールルートの公開契約、依存方向、ベースパッケージ直下の全体設定を確認するときに読む。
tags: [architecture, backend, spring-modulith]
---

# バックエンドアーキテクチャ

最上位を機能で分割し、各機能モジュールを `com.example.demo.<feature>` に置く。
機能の内部はオニオンアーキテクチャとし、依存を外側から内側へ限定する。
他の機能モジュールから参照できるのは、モジュールルートの公開契約だけである。
各層の責務は [バックエンドの層の責務](layers.md) に示す。

## 方針

バックエンドは、最上位を機能で分割する Package by feature を採用する。

各機能の内部にはオニオンアーキテクチャを適用し、業務ロジックから技術詳細へ向かう依存を禁止する。

Spring Modulith は `com.example.demo` の直接サブパッケージをアプリケーションモジュールとして認識するため、**機能モジュール**を `com.example.demo.<feature>` に置く。

依存方向は外側から内側へ限定し、Presentation と Infrastructure は Application を介して Domain のユースケースを実行する。

この方針は [ADR-002](../adr/ADR-002-package-by-feature-onion-architecture.md) で決定している。

## パッケージ構成

機能モジュールは、必要な役割が生じたパッケージだけを作る。

``` text
backend/src/main/java/com/example/demo/
├── DemoApplication.java
├── SecurityConfig.java
├── OpenTelemetryAppenderInitializer.java
├── package-info.java
│
└── <feature>/
    ├── package-info.java
    ├── <Feature>Operations.java
    ├── <Command>.java
    ├── <Result>.java
    ├── <Event>.java
    │
    ├── domain/
    │   ├── model/
    │   │   ├── package-info.java
    │   │   ├── <Aggregate>.java
    │   │   ├── <Entity>.java
    │   │   └── <ValueObject>.java
    │   └── service/
    │       ├── package-info.java
    │       └── <DomainService>.java
    │
    ├── application/
    │   ├── package-info.java
    │   ├── <UseCase>Service.java
    │   └── port/
    │       ├── package-info.java
    │       └── <RepositoryPort>.java
    │
    ├── presentation/
    │   └── web/
    │       ├── package-info.java
    │       ├── <Feature>Controller.java
    │       ├── <Feature>Request.java
    │       └── <Feature>Response.java
    │
    └── infrastructure/
        ├── persistence/
        │   ├── package-info.java
        │   ├── Jooq<Feature>Repository.java
        │   └── <Feature>RecordMapper.java
        ├── messaging/
        │   ├── package-info.java
        │   ├── <Event>Listener.java
        │   └── <Event>Publisher.java
        └── client/
            ├── package-info.java
            └── <ExternalSystem>Client.java
```

空ディレクトリは先に作らない。

`domain.service`、`application.port`、各 Infrastructure Adapter は、その役割を持つ型が必要になった時点で追加する。

Java パッケージを追加するときは、既存の NullAway と JSpecify の規約に従い、`@NullMarked` を宣言する `package-info.java` も追加する。

## モジュールルート

**モジュールルート**は `com.example.demo.<feature>` 直下のパッケージであり、他の機能モジュールへ公開する契約だけを置く。

公開できる型は、ユースケースインタフェース、コマンド、結果、他モジュールへ通知するイベントである。

Spring MVC の Request と Response、Controller、jOOQ の生成型、Repository 実装は置かない。

入口側の契約はモジュールルートで表現できるため、`application.port.in` は作らない。

外部依存を反転する必要が生じた場合だけ、Application に `port` を追加する。

Spring Modulith の既定の閉じたモジュールで足りるため、通常は `@ApplicationModule` を付けない。

ルート以外のパッケージを公開する必要が生じた場合だけ `@NamedInterface` を使う。

## 依存方向

許可する主要な依存方向は次のとおりである。

``` text
presentation ─┐
persistence ──┤
messaging ────┼─> application ─> domain.service ─> domain.model
client ───────┘
```

外側の Adapter は必要に応じて Domain を直接利用できるが、Domain と Application から外側の実装は参照できない。

機能モジュールをまたぐ参照は、相手モジュールのルートに置いた公開契約だけに限定する。

他モジュールの `domain`、`application`、`presentation`、`infrastructure` は内部パッケージなので参照しない。

## 全体設定

`com.example.demo` 直下には、アプリケーションの起動クラスと機能横断の設定だけを置く。

現在は `DemoApplication`、`SecurityConfig`、`OpenTelemetryAppenderInitializer` が該当する。

機能固有の Bean や業務ロジックをベースパッケージ直下へ追加しない。

共通パッケージを安易に `com.example.demo` の直接サブパッケージへ作ると、Spring Modulith が機能モジュールとして認識するため避ける。

複数の機能で似た処理が必要になっても、共有される概念と安定した境界が明確になるまでは各機能内に置く。

例外として、`com.example.demo.shared` を Spring Modulith の shared モジュールにする（[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）。
shared モジュールには永続化の技術的な共通処理だけを置き、業務の概念を置かない。
共通処理は `shared.infrastructure.persistence` に置き、`@NamedInterface` で公開する。
これを使うのは、他のモジュールの `infrastructure.persistence` のアダプターだけとする。

## 関連資料

- [ADR-002: package by feature とオニオンアーキテクチャ](../adr/ADR-002-package-by-feature-onion-architecture.md)
- [バックエンドの層の責務](layers.md)
- [バックエンドの Java 実装規約](java-coding.md)
- [バックエンドのアーキテクチャテスト](architecture-tests.md)
- [バックエンドの機能追加時の確認](runbook-add-feature.md)
- [バックエンドの文書一覧（テスト規約を含む）](index.md)
